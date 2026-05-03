# Data Generator — Plan 9: Provisioning Emit + Apply

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec §4. Add a per-scenario `provisioning:` enum (`apply` / `emit` / `skip`, default `skip`). `emit` writes GG8 cache XML and GG9 SQL DDL to `<demoOutputDirectory>/data-generator/provisioning/`. `apply` connects to the cluster and creates absent caches/tables idempotently. The `affinity: true` annotation (parsed since Plan 5 but unused) is finally consumed: GG8 → `CacheKeyConfiguration.affinityKeyFieldName`; GG9 → `COLOCATE BY (...)`. Closes **F6** — TRANSACTIONAL cache mode whenever the scenario uses `business_event` transactions.

**Architecture:** A GG-agnostic `ProvisioningPlan` is built in `data-generator-core` from `(DataConfig, ScenarioSpec)` — a flat list of `SchemaDescriptor`s. Each per-flavor subproject contributes a `Provisioner` plus a pure renderer. **`Gg8Main` / `Gg9Main` invoke the flavor's `Provisioner` before constructing the runtime `Target`; `ScenarioRunnerCli` stays GG-agnostic and never imports any `Provisioner`.** Rationale: impls live in different gradle modules — passing a function pointer through core would either leak a flavor-specific shape into core or pull `ignite-*` into core. Each `Main` already constructs the flavor-specific `Target`; adding the provisioning step a few lines above is the natural extension. `Provisioner` is a plain `interface` (not sealed) for the same reason.

**Tech Stack:** No new dependencies. Reuses `org.gridgain:ignite-core:8.9.18` (`-gg8` `api`) for `ClientCacheConfiguration`, `CacheKeyConfiguration`, `CacheAtomicityMode`; and `org.gridgain:ignite-client:9.1.3` (`-gg9` `api`) for `IgniteSql`. Renderers emit plain `String`. SnakeYAML pinned at `1.33`.

---

## Pre-execution prerequisites

1. Post-Plan-7.5 `main` (three subprojects, 148 tests pass via `./gradlew clean test`).
2. `gridgain-demo-client-utils` is published to maven local.
3. F6 in ROADMAP.md is deferred to this plan; Task 8 closes it.
4. `ColumnSpec.affinity` is parsed but unused; Task 4 adds the validator; Tasks 6 + 10 are the first consumers.
5. Env-gated integration tests skip when env vars are absent — never load-bearing for CI without a cluster.

---

## Spec extensions (additive)

```yaml
scenarios:
  - name: customer-load
    target: gg8-cluster
    # ...
    transaction_scope: business_event
    provisioning: apply      # NEW. skip (default) | emit | apply
    read_ratio: 0.0
```

**No `schema_version` bump.** Field is optional with default `skip` — same additive-enum shape Plan 5/6 used for `transaction_scope`. **No `data.yaml` changes.**

---

## Target architecture

```
data-generator-core/
├── config/{ProvisioningMode.kt,ScenarioSpec.kt,CrossElementValidator.kt}  # ADD enum + field + validator
├── provisioning/{SqlType,ColumnDescriptor,SchemaDescriptor,ProvisioningPlan,
│                 ProvisioningOutcome,Provisioner,ProvisioningPlanFactory}.kt  # ALL NEW
├── output/OutputLayout.kt                                                  # ADD provisioningGg8/Gg9
└── resources/schema/ops/v2.schema.json                                     # ADD provisioning enum

data-generator-gg8/{provisioning/{Gg8CacheXmlRenderer,Gg8XmlProvisioner}.kt, cli/Gg8Main.kt}
data-generator-gg9/{provisioning/{Gg9SqlDdlRenderer,Gg9SqlProvisioner}.kt,  cli/Gg9Main.kt}
```

**Type inference (column → SqlType) — known limitation.** `data.yaml` doesn't declare types; `ProvisioningPlanFactory.inferType` maps each `ValueSourceSpec` subclass:

| Subclass | SqlType |
|---|---|
| `SequenceSpec` | `BIGINT` |
| `KeySuffixSpec`, `DataFakerSpec`, `WeightedChoiceSpec`, `YamlDataSpec`, `UniqueSpec` | `VARCHAR` |
| `ParentFkRefSpec` | inherited from referenced parent column (falls back to `VARCHAR`) |

Numeric `WeightedChoiceSpec.choices` widen to text in v1 — captured as follow-up F10 in Task 14.

---

## Tasks

### Task 1: Add `ProvisioningMode` enum + `ScenarioSpec` field + JSONSchema update

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ProvisioningMode.kt`
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt`
- Modify: `data-generator-core/src/main/resources/schema/ops/v2.schema.json`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ProvisioningModeDeserializationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ProvisioningModeDeserializationTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper
    private val base = """
        name: s1
        target: t1
        root_schemas: [customer]
        rate: { kind: constant, ops_per_second: 1.0 }
        duration: { kind: count, value: 1 }
        read_ratio: 0.0
    """.trimIndent()

    @Test fun `defaults to SKIP when omitted`() {
        val s: ScenarioSpec = mapper.readValue(base, ScenarioSpec::class.java)
        assertThat(s.provisioning).isEqualTo(ProvisioningMode.SKIP)
    }
    @Test fun `parses skip`() = check("skip", ProvisioningMode.SKIP)
    @Test fun `parses emit`() = check("emit", ProvisioningMode.EMIT)
    @Test fun `parses apply`() = check("apply", ProvisioningMode.APPLY)

    private fun check(yaml: String, expected: ProvisioningMode) {
        val s: ScenarioSpec = mapper.readValue("$base\nprovisioning: $yaml", ScenarioSpec::class.java)
        assertThat(s.provisioning).isEqualTo(expected)
    }
}
```

- [ ] **Step 2: Run from `gridgain-demo-data-generator/` and verify FAIL** — `./gradlew :data-generator-core:test --tests '*ProvisioningModeDeserializationTest'` (`ProvisioningMode` doesn't exist).

- [ ] **Step 3: Create `ProvisioningMode.kt`**

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Per-scenario provisioning toggle. Default `SKIP` matches the spec (§4).
 *
 * NOTE: this enum carries a default of `SKIP` in violation of the workspace project rule
 * "no defaults on template classes" — same documented exception as `ScenarioSpec.transactionScope`.
 * The default is the safest value (no side effect).
 */
enum class ProvisioningMode {
    @JsonProperty("skip") SKIP,
    @JsonProperty("emit") EMIT,
    @JsonProperty("apply") APPLY,
}
```

- [ ] **Step 4: Add `provisioning` to `ScenarioSpec.kt`**

In `ScenarioSpec.kt`, replace the `data class ScenarioSpec(...)` declaration:

```kotlin
data class ScenarioSpec(
    val name: String,
    val target: String = "",
    @JsonProperty("root_schemas") val rootSchemas: List<String>,
    val rate: RateSpec,
    val duration: DurationSpec,
    @JsonProperty("stop_conditions") val stopConditions: List<StopConditionSpec> = emptyList(),
    @JsonProperty("transaction_scope") val transactionScope: TransactionScope = TransactionScope.NONE,
    val provisioning: ProvisioningMode = ProvisioningMode.SKIP,
    @JsonProperty("read_ratio") val readRatio: Double,
)
```

- [ ] **Step 5: Add `provisioning` to v2 ops JSONSchema**

In `v2.schema.json`, inside `$defs.scenario.properties`, between `transaction_scope` and `read_ratio`, add:

```json
"provisioning": { "enum": ["skip", "emit", "apply"], "default": "skip" },
```

- [ ] **Step 6: Run + commit**

```bash
./gradlew :data-generator-core:test  # expect: full core suite green; provisioning tests 4/4 pass

git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ProvisioningMode.kt \
        data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt \
        data-generator-core/src/main/resources/schema/ops/v2.schema.json \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ProvisioningModeDeserializationTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): add ScenarioSpec.provisioning enum (Plan 9 Task 1)

Per-scenario toggle: skip (default), emit, apply. Updates v2 ops
JSONSchema. Tasks 6-13 implement emit + apply pathways.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add `provisioningGg8` + `provisioningGg9` to `OutputLayout`

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/output/OutputLayout.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/output/OutputLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class OutputLayoutTest {
    @Test fun `provisioningGg8 sits under provisioning`(@TempDir tmp: Path) {
        val l = OutputLayout(tmp)
        assertThat(l.provisioningGg8).isEqualTo(tmp.resolve("data-generator/provisioning/gg8"))
    }
    @Test fun `provisioningGg9 sits under provisioning`(@TempDir tmp: Path) {
        val l = OutputLayout(tmp)
        assertThat(l.provisioningGg9).isEqualTo(tmp.resolve("data-generator/provisioning/gg9"))
    }
    @Test fun `ensureBaseDirectories creates both flavor subdirs`(@TempDir tmp: Path) {
        val l = OutputLayout(tmp).also { it.ensureBaseDirectories() }
        assertThat(Files.isDirectory(l.provisioningGg8)).isTrue()
        assertThat(Files.isDirectory(l.provisioningGg9)).isTrue()
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-core:test --tests '*OutputLayoutTest'` (`provisioningGg8`/`Gg9` don't exist).

- [ ] **Step 3: Edit `OutputLayout.kt`**

```kotlin
package com.gridgain.demo.datagen.output

import java.nio.file.Files
import java.nio.file.Path

class OutputLayout(demoOutputDirectory: Path) {
    val generatorRoot: Path = demoOutputDirectory.resolve("data-generator")
    val provisioning: Path = generatorRoot.resolve("provisioning")
    val provisioningGg8: Path = provisioning.resolve("gg8")
    val provisioningGg9: Path = provisioning.resolve("gg9")
    val state: Path = generatorRoot.resolve("state")
    val stateFile: Path = state.resolve("state.yaml")
    private val runs: Path = generatorRoot.resolve("runs")

    fun runDir(runId: String): Path = runs.resolve(runId)
    fun resultFile(runId: String): Path = runDir(runId).resolve("result.yaml")
    fun runLogFile(runId: String): Path = runDir(runId).resolve("run.log.yaml")

    fun ensureBaseDirectories() {
        Files.createDirectories(generatorRoot)
        Files.createDirectories(provisioning)
        Files.createDirectories(provisioningGg8)
        Files.createDirectories(provisioningGg9)
        Files.createDirectories(state)
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :data-generator-core:test --tests '*OutputLayoutTest'  # expect 3/3 pass
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/output/OutputLayout.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/output/OutputLayoutTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): OutputLayout exposes per-flavor provisioning subdirs (Plan 9 Task 2)

Adds provisioningGg8 and provisioningGg9 paths plus mkdir-p in
ensureBaseDirectories. Tasks 7 and 11 use these as emit destinations.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Define `ProvisioningPlan` types + `Provisioner` interface in core

**Files (all under `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/provisioning/`):**
- Create: `SqlType.kt`, `ColumnDescriptor.kt`, `SchemaDescriptor.kt`, `ProvisioningPlan.kt`, `ProvisioningOutcome.kt`, `Provisioner.kt`

Pure data + interface — no test in this task; Task 5 tests the shape via `ProvisioningPlanFactory`.

- [ ] **Step 1: Create the six files**

`SqlType.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

/** SQL column types Plan 9 supports. Inferred by ProvisioningPlanFactory.inferType.
 *  Renderers translate to flavor-specific types. F10 captures future extension. */
enum class SqlType { BIGINT, VARCHAR }
```

`ColumnDescriptor.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

data class ColumnDescriptor(val name: String, val type: SqlType, val isKey: Boolean, val isAffinity: Boolean)
```

`SchemaDescriptor.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

/** `transactional` is true when scenario.transactionScope == BUSINESS_EVENT — drives
 *  GG8 CacheAtomicityMode.TRANSACTIONAL; carries no DDL effect for GG9.
 *  `affinityColumn` is null when no column declares `affinity: true`. */
data class SchemaDescriptor(
    val schemaName: String,
    val keyColumn: String,
    val affinityColumn: String?,
    val columns: List<ColumnDescriptor>,
    val transactional: Boolean,
)
```

`ProvisioningPlan.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

data class ProvisioningPlan(val descriptors: List<SchemaDescriptor>)
```

`ProvisioningOutcome.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

import java.nio.file.Path

/** artifactsWritten: emit() output. cachesOrTablesCreated/AlreadyExisted: apply() outputs.
 *  errors: non-empty means the run failed (rich messages, not codes). */
data class ProvisioningOutcome(
    val artifactsWritten: List<Path>,
    val cachesOrTablesCreated: List<String>,
    val cachesOrTablesAlreadyExisted: List<String>,
    val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}
```

`Provisioner.kt`:
```kotlin
package com.gridgain.demo.datagen.provisioning

import java.nio.file.Path

/** Plain interface (impls live in different gradle modules). Both methods MUST be idempotent. */
interface Provisioner {
    fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome
    fun apply(plan: ProvisioningPlan): ProvisioningOutcome
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :data-generator-core:compileKotlin :data-generator-core:test
```

Expected: BUILD SUCCESSFUL — no callers yet, validates the symbols compile.

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/provisioning/
git commit -m "$(cat <<'EOF'
feat(datagen): introduce GG-agnostic ProvisioningPlan + Provisioner (Plan 9 Task 3)

Adds the data shape for provisioning. Flavor-specific renderers and
provisioners (Tasks 6-13) consume these types.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `AffinityColumnValidator` — at most one `affinity: true` per schema

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/AffinityColumnValidatorTest.kt`

Spec §1 + §4: `affinity: true` marks the colocation key — zero or one per schema. Today nothing checks this. Without the rule, multi-affinity rows would silently first-wins. (We keep the validator narrow — the broader "must be the key column or a parent-fk-ref column" check is deferred for follow-on discussion.)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AffinityColumnValidatorTest {
    private val validator = AffinityColumnValidator()
    private val emptyOps = OpsConfig(schemaVersion = 2, scenarios = emptyList())
    private fun col(name: String, affinity: Boolean = false, key: Boolean = false) =
        ColumnSpec(name = name, nullRate = 0.0, affinity = affinity, key = key,
                   valueSource = SequenceSpec(start = 1, step = 1))

    @Test fun `passes with no affinity column`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0, listOf(col("id", key = true))))), emptyOps)
        assertThat(r.errors).isEmpty()
    }
    @Test fun `passes with exactly one affinity column`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0,
            listOf(col("id", key = true, affinity = true), col("name"))))), emptyOps)
        assertThat(r.errors).isEmpty()
    }
    @Test fun `fails with multiple affinity columns`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0,
            listOf(col("id", key = true, affinity = true), col("name", affinity = true))))), emptyOps)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("schema 'c'")
            .contains("more than one affinity column")
            .contains("id, name")
            .contains("Mark exactly one column with 'affinity: true'")
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-core:test --tests '*AffinityColumnValidatorTest'` (symbol doesn't exist).

- [ ] **Step 3: Append the validator to `CrossElementValidator.kt`**

```kotlin
class AffinityColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            val affs = schema.columns.filter { it.affinity }
            if (affs.size > 1) {
                errors += "schema '${schema.name}' has more than one affinity column " +
                    "(${affs.joinToString(", ") { it.name }}). " +
                    "Mark exactly one column with 'affinity: true' (or none) — multiple " +
                    "affinity columns have undefined colocation semantics."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
```

- [ ] **Step 4: Register in `ConfigurationParser.kt`**

In the `crossElementValidator` default `CompositeCrossElementValidator(listOf(...))`, append `AffinityColumnValidator()` after `KeyColumnValidator()`.

- [ ] **Step 5: Run + commit**

```bash
./gradlew :data-generator-core:test  # expect: BUILD SUCCESSFUL (no fixture has multi-affinity)
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt \
        data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/AffinityColumnValidatorTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): AffinityColumnValidator rejects multi-affinity per schema (Plan 9 Task 4)

Spec section 1: affinity: true marks the colocation key. Multi-affinity
has undefined semantics — reject early. Wired into the default validator
chain. Plan 9 renderers (Tasks 6, 10) finally consume the field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `ProvisioningPlanFactory`

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/provisioning/ProvisioningPlanFactory.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/provisioning/ProvisioningPlanFactoryTest.kt`

GG-agnostic. Walks `data.schemas`, picks `key: true` column as `keyColumn`, picks `affinity: true` as `affinityColumn` (or null), infers `SqlType` per column, sets `transactional` from `scenario.transactionScope == BUSINESS_EVENT`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.*
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ProvisioningPlanFactoryTest {
    private fun col(name: String, vs: ValueSourceSpec, key: Boolean = false, affinity: Boolean = false) =
        ColumnSpec(name = name, nullRate = 0.0, affinity = affinity, key = key, valueSource = vs)
    private fun scenario(scope: TransactionScope = TransactionScope.NONE) =
        ScenarioSpec(name = "s1", target = "t1", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1.0), duration = CountDurationSpec(1L),
            transactionScope = scope, readRatio = 0.0)

    @Test fun `simple schema with sequence key`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1), key = true),
            col("name", DataFakerSpec("#{name.fullName}")),
        ))))
        val d = ProvisioningPlanFactory.from(data, scenario()).descriptors.single()
        assertThat(d.schemaName).isEqualTo("customer")
        assertThat(d.keyColumn).isEqualTo("id")
        assertThat(d.affinityColumn).isNull()
        assertThat(d.transactional).isFalse()
        assertThat(d.columns).extracting<String> { it.name }.containsExactly("id", "name")
        assertThat(d.columns).extracting<SqlType> { it.type }.containsExactly(SqlType.BIGINT, SqlType.VARCHAR)
    }

    @Test fun `transactional true when scenario uses business_event`() {
        val data = DataConfig(2, listOf(SchemaSpec("c", 0.0, listOf(col("id", SequenceSpec(1, 1), key = true)))))
        val plan = ProvisioningPlanFactory.from(data, scenario(scope = TransactionScope.BUSINESS_EVENT))
        assertThat(plan.descriptors.single().transactional).isTrue()
    }

    @Test fun `affinity column captured and parent-fk-ref inherits parent SqlType`() {
        val data = DataConfig(2, listOf(
            SchemaSpec("customer", 0.0, listOf(col("id", SequenceSpec(1, 1), key = true))),
            SchemaSpec("order", 0.0, listOf(
                col("customer_id", ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1))), affinity = true),
                col("id", KeySuffixSpec("customer_id", "-", 6), key = true),
            )),
        ))
        val d = ProvisioningPlanFactory.from(data, scenario()).descriptors.first { it.schemaName == "order" }
        assertThat(d.affinityColumn).isEqualTo("customer_id")
        assertThat(d.keyColumn).isEqualTo("id")
        assertThat(d.columns.first { it.name == "customer_id" }.type).isEqualTo(SqlType.BIGINT)  // inherited from customer.id
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-core:test --tests '*ProvisioningPlanFactoryTest'`

- [ ] **Step 3: Create `ProvisioningPlanFactory.kt`**

```kotlin
package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.*

/**
 * Builds a [ProvisioningPlan] from `(DataConfig, ScenarioSpec)`.
 *
 * Per-column SqlType inference is value-source-driven (data.yaml does not declare types):
 *   SequenceSpec        -> BIGINT
 *   KeySuffixSpec, DataFakerSpec, WeightedChoiceSpec, YamlDataSpec, UniqueSpec -> VARCHAR
 *   ParentFkRefSpec     -> inherited from parent col; falls back to VARCHAR if unresolved.
 *
 * `transactional` is set when scenario.transactionScope == BUSINESS_EVENT, applied to every
 * descriptor. Plan 9 closes follow-up F6 by emitting CacheAtomicityMode.TRANSACTIONAL on GG8
 * when this flag is true.
 */
object ProvisioningPlanFactory {

    fun from(data: DataConfig, scenario: ScenarioSpec): ProvisioningPlan {
        val schemasByName = data.schemas.associateBy { it.name }
        val transactional = scenario.transactionScope == TransactionScope.BUSINESS_EVENT
        val descriptors = data.schemas.map { schema ->
            val keyColumn = schema.columns.firstOrNull { it.key }?.name
                ?: error("schema '${schema.name}' has no key column — KeyColumnValidator should have rejected this earlier")
            val affinityColumn = schema.columns.firstOrNull { it.affinity }?.name
            val cols = schema.columns.map { col ->
                ColumnDescriptor(
                    name = col.name,
                    type = inferType(col.valueSource, schemasByName),
                    isKey = col.key,
                    isAffinity = col.affinity,
                )
            }
            SchemaDescriptor(schema.name, keyColumn, affinityColumn, cols, transactional)
        }
        return ProvisioningPlan(descriptors)
    }

    private fun inferType(vs: ValueSourceSpec, schemasByName: Map<String, SchemaSpec>): SqlType =
        when (vs) {
            is SequenceSpec -> SqlType.BIGINT
            is KeySuffixSpec, is DataFakerSpec, is WeightedChoiceSpec, is YamlDataSpec, is UniqueSpec -> SqlType.VARCHAR
            is ParentFkRefSpec -> {
                val parentCol = schemasByName[vs.parentSchema]?.columns?.firstOrNull { it.name == vs.parentColumn }
                if (parentCol != null) inferType(parentCol.valueSource, schemasByName) else SqlType.VARCHAR
            }
        }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :data-generator-core:test --tests '*ProvisioningPlanFactoryTest'  # expect 3/3 pass
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/provisioning/ProvisioningPlanFactory.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/provisioning/ProvisioningPlanFactoryTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): ProvisioningPlanFactory builds plans from (data, scenario) (Plan 9 Task 5)

Pure GG-agnostic factory. SqlType inference table maps each value source
to BIGINT or VARCHAR. ParentFkRefSpec inherits from referenced parent.
Transactional flag set per-scenario based on transaction_scope.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: GG8 — `Gg8CacheXmlRenderer`

**Files:**
- Create: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8CacheXmlRenderer.kt`
- Test: `data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8CacheXmlRendererTest.kt`

Pure renderer: one `SchemaDescriptor` → one Spring-bean XML.

- [ ] **Step 1: Write the failing golden tests**

Two cases (atomic-no-affinity and transactional-with-affinity) lock the byte-for-byte output shape:

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg8CacheXmlRendererTest {
    private val renderer = Gg8CacheXmlRenderer()

    @Test fun `atomic cache no affinity`() {
        val d = SchemaDescriptor("customer", "id", null,
            listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="customer"/>
                    <property name="atomicityMode" value="ATOMIC"/>
                </bean>
            </beans>
            """.trimIndent())
    }

    @Test fun `transactional cache with affinity column`() {
        val d = SchemaDescriptor("order", "id", "customer_id",
            listOf(ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                   ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false)),
            true)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="order"/>
                    <property name="atomicityMode" value="TRANSACTIONAL"/>
                    <property name="keyConfiguration">
                        <list>
                            <bean class="org.apache.ignite.cache.CacheKeyConfiguration">
                                <constructor-arg index="0" value="java.lang.Object"/>
                                <constructor-arg index="1" value="customer_id"/>
                            </bean>
                        </list>
                    </property>
                </bean>
            </beans>
            """.trimIndent())
    }
}
```

- [ ] **Step 2: Run to verify FAIL** — `./gradlew :data-generator-gg8:test --tests '*Gg8CacheXmlRendererTest'`

- [ ] **Step 3: Create the renderer**

```kotlin
package com.gridgain.demo.datagen.provisioning

/**
 * Pure renderer: one [SchemaDescriptor] -> one Spring-bean CacheConfiguration XML.
 *
 * - `atomicityMode` is ATOMIC by default; TRANSACTIONAL when descriptor.transactional is true.
 * - When affinityColumn is non-null, a `<property name="keyConfiguration">` block carries a single
 *   CacheKeyConfiguration whose constructor args are ("java.lang.Object", <affinityColumn>).
 *   Matches the runtime path in Gg8XmlProvisioner.apply (Task 8).
 *
 * No XML library — string assembly is sufficient for the deterministic output the golden tests lock.
 */
class Gg8CacheXmlRenderer {
    fun render(d: SchemaDescriptor): String {
        val mode = if (d.transactional) "TRANSACTIONAL" else "ATOMIC"
        val sb = StringBuilder()
        sb.append("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="${d.schemaName}"/>
                    <property name="atomicityMode" value="$mode"/>
        """.trimIndent())
        if (d.affinityColumn != null) {
            sb.append('\n')
            sb.append("""
                    <property name="keyConfiguration">
                        <list>
                            <bean class="org.apache.ignite.cache.CacheKeyConfiguration">
                                <constructor-arg index="0" value="java.lang.Object"/>
                                <constructor-arg index="1" value="${d.affinityColumn}"/>
                            </bean>
                        </list>
                    </property>
            """.trimIndent())
        }
        sb.append('\n')
        sb.append("""
                </bean>
            </beans>
        """.trimIndent())
        return sb.toString()
    }
}
```

(If the assembled output drifts whitespace from the golden test by even one space, fix the renderer — the golden test is the contract. The implementer may use any string-building approach as long as the bytes match.)

- [ ] **Step 4: Run tests + commit**

```bash
./gradlew :data-generator-gg8:test --tests '*Gg8CacheXmlRendererTest'  # expect 2/2 pass
git add data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8CacheXmlRenderer.kt \
        data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8CacheXmlRendererTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg8): Gg8CacheXmlRenderer (Plan 9 Task 6)

Pure renderer. Two golden tests lock byte-for-byte output: atomic+no-
affinity, transactional+affinity.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: GG8 — `Gg8XmlProvisioner.emit`

**Files:**
- Create: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisioner.kt`
- Test: `data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisionerEmitTest.kt`

One `<schemaName>.xml` file per descriptor.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class Gg8XmlProvisionerEmitTest {
    private val provisioner = Gg8XmlProvisioner(clusterName = "unused-for-emit")

    @Test fun `emit writes one xml file per schema`(@TempDir dest: Path) {
        val plan = ProvisioningPlan(listOf(
            SchemaDescriptor("customer", "id", null,
                listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false),
            SchemaDescriptor("order", "id", "customer_id", listOf(
                ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false),
            ), true),
        ))
        val outcome = provisioner.emit(plan, dest)
        assertThat(outcome.errors).isEmpty()
        assertThat(outcome.artifactsWritten).containsExactly(dest.resolve("customer.xml"), dest.resolve("order.xml"))
        assertThat(Files.readString(dest.resolve("customer.xml")))
            .contains("name=\"customer\"").contains("ATOMIC").doesNotContain("keyConfiguration")
        assertThat(Files.readString(dest.resolve("order.xml")))
            .contains("name=\"order\"").contains("TRANSACTIONAL").contains("customer_id")
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-gg8:test --tests '*Gg8XmlProvisionerEmitTest'`

- [ ] **Step 3: Create `Gg8XmlProvisioner.kt` (emit only — `apply` lands in Task 8)**

```kotlin
package com.gridgain.demo.datagen.provisioning

import java.nio.file.Files
import java.nio.file.Path

class Gg8XmlProvisioner(
    private val clusterName: String,
    private val renderer: Gg8CacheXmlRenderer = Gg8CacheXmlRenderer(),
) : Provisioner {

    override fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome {
        if (!Files.isDirectory(destinationDir)) Files.createDirectories(destinationDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        for (d in plan.descriptors) {
            try {
                val path = destinationDir.resolve("${d.schemaName}.xml")
                Files.writeString(path, renderer.render(d))
                written += path
            } catch (e: Exception) {
                errors += "Gg8XmlProvisioner.emit failed for schema '${d.schemaName}' " +
                    "writing under $destinationDir: ${e.message}. " +
                    "Verify the destination is writable and the schema name is a valid filename."
            }
        }
        return ProvisioningOutcome(written, emptyList(), emptyList(), errors)
    }

    override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
        // Implemented in Task 8.
        throw NotImplementedError("Gg8XmlProvisioner.apply lands in Plan 9 Task 8")
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :data-generator-gg8:test --tests '*Gg8XmlProvisionerEmitTest'  # expect pass
git add data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisioner.kt \
        data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisionerEmitTest.kt
git commit -m "feat(datagen-gg8): Gg8XmlProvisioner.emit writes per-schema cache XML (Plan 9 Task 7)

apply() lands in Task 8.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: GG8 — `Gg8XmlProvisioner.apply` (closes F6)

**Files:**
- Modify: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisioner.kt`
- Test: `data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisionerApplyTest.kt` (env-gated)

Opens `IgniteClient` via `DemoAddressFinder` + `ClientConfiguration` (same shape as `Gg8KvTarget`). Per descriptor, builds a `ClientCacheConfiguration` with `setName`, `setAtomicityMode`, and (when affinity) `setKeyConfiguration(CacheKeyConfiguration("java.lang.Object", affinityColumn))`, then calls `getOrCreateCache(cfg)`. GG8's `getOrCreateCache(cfg)` is idempotent — a cache with matching config is left alone; a mismatch throws.

Closes **F6**.

- [ ] **Step 1: Write the failing env-gated test**

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8XmlProvisionerApplyTest {
    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test_provisioned"

    @Test fun `apply creates a transactional cache, second run is a no-op`() {
        val provisioner = Gg8XmlProvisioner(clusterName)
        val plan = ProvisioningPlan(listOf(SchemaDescriptor(
            schemaName = cacheName, keyColumn = "id", affinityColumn = "id",
            columns = listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = true)),
            transactional = true)))
        val first = provisioner.apply(plan)
        assertThat(first.errors).isEmpty()
        val second = provisioner.apply(plan)
        assertThat(second.errors).isEmpty()
        assertThat(second.cachesOrTablesCreated).isEmpty()
        assertThat(second.cachesOrTablesAlreadyExisted).contains(cacheName)
    }
}
```

- [ ] **Step 2: Run + verify SKIPPED** — `./gradlew :data-generator-gg8:test --tests '*Gg8XmlProvisionerApplyTest'` (skipped without `DATAGEN_GG8_CLUSTER_NAME`; with env: FAIL because `apply` throws `NotImplementedError`).

- [ ] **Step 3: Implement `apply`**

In `Gg8XmlProvisioner.kt`, add imports at the top:

```kotlin
import com.gridgain.demo.client.gg8.DemoAddressFinder
import org.apache.ignite.Ignition
import org.apache.ignite.cache.CacheAtomicityMode
import org.apache.ignite.cache.CacheKeyConfiguration
import org.apache.ignite.client.ClientCacheConfiguration
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration
```

Replace the `apply` method body:

```kotlin
override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
    val cfg = ClientConfiguration().setAddressesFinder(DemoAddressFinder(clusterName))
    val created = mutableListOf<String>()
    val existed = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val client: IgniteClient = try {
        Ignition.startClient(cfg)
    } catch (e: Exception) {
        return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
            "Gg8XmlProvisioner.apply could not connect to GG8 cluster '$clusterName': ${e.message}. " +
            "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
            "and the cluster name matches the clusters[].name entry."
        ))
    }
    client.use { ignite ->
        val existing: Set<String> = ignite.cacheNames().toSet()
        for (d in plan.descriptors) {
            val alreadyExists = d.schemaName in existing
            try {
                val cacheCfg = ClientCacheConfiguration().apply {
                    setName(d.schemaName)
                    setAtomicityMode(if (d.transactional) CacheAtomicityMode.TRANSACTIONAL else CacheAtomicityMode.ATOMIC)
                    d.affinityColumn?.let { setKeyConfiguration(CacheKeyConfiguration("java.lang.Object", it)) }
                }
                ignite.getOrCreateCache<Any, Any>(cacheCfg)
                if (alreadyExists) existed += d.schemaName else created += d.schemaName
            } catch (e: Exception) {
                errors += "Gg8XmlProvisioner.apply failed for cache '${d.schemaName}': ${e.message}. " +
                    "If the cache already exists with a different config (atomicityMode, affinityKey, or " +
                    "backups), GG8's getOrCreateCache rejects the call. Either tear down the cache and re-run, " +
                    "or align data.yaml to the existing cache's config."
            }
        }
    }
    return ProvisioningOutcome(emptyList(), created, existed, errors)
}
```

- [ ] **Step 4: Run env-gated test (when cluster available) + full gg8 suite + commit**

```bash
# With env vars set, the live test passes; without, it skips.
DATAGEN_GG8_CLUSTER_NAME=<cluster> DATAGEN_GG8_TEST_CACHE=data_gen_test_provisioned \
GG_DEMO_CLIENT_ENDPOINTS=<absolute path> \
  ./gradlew :data-generator-gg8:test --tests '*Gg8XmlProvisionerApplyTest'
./gradlew :data-generator-gg8:test  # expect BUILD SUCCESSFUL

git add data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisioner.kt \
        data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg8XmlProvisionerApplyTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg8): Gg8XmlProvisioner.apply via ClientCacheConfiguration (Plan 9 Task 8)

Idempotent through getOrCreateCache(cfg). TRANSACTIONAL atomicityMode
applied when descriptor.transactional is true — closes follow-up F6.
CacheKeyConfiguration("java.lang.Object", affinityColumn) wires the
affinity key when set. Mismatch errors carry rich remediation guidance.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: GG8 — wire `Gg8Main` to invoke the provisioner

**Files:**
- Modify: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg8Main.kt`

Reads `resolution.scenario.provisioning`. EMIT writes under `layout.provisioningGg8`; APPLY runs against the cluster; SKIP bypasses. The provisioning step is a pre-step — the scenario then runs against the (possibly newly-provisioned) cluster.

- [ ] **Step 1: Replace `Gg8Main.kt`**

```kotlin
@file:JvmName("Gg8Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.config.ProvisioningMode
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.provisioning.Gg8XmlProvisioner
import com.gridgain.demo.datagen.provisioning.ProvisioningOutcome
import com.gridgain.demo.datagen.provisioning.ProvisioningPlanFactory
import com.gridgain.demo.datagen.target.Gg8KvTarget
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = ScenarioRunnerCli.defaultLogger()
    try {
        val resolution = ScenarioRunnerCli.resolve(parsed, logger)
        val spec = resolution.targetSpec as? Gg8KvTargetSpec
            ?: throw MisconfigurationException(
                "Gg8Main was launched but the resolved target '${resolution.targetSpec.name}' is " +
                "not a gg8-kv target (kind=${resolution.targetSpec::class.simpleName}). " +
                "The plugin's DataGenerateTask is supposed to dispatch the right flavor — " +
                "if running directly, invoke Gg9Main instead."
            )

        val mode = resolution.scenario.provisioning
        if (mode != ProvisioningMode.SKIP) {
            val plan = ProvisioningPlanFactory.from(resolution.parsedConfig.data, resolution.scenario)
            val provisioner = Gg8XmlProvisioner(clusterName = spec.clusterName)
            val outcome: ProvisioningOutcome = when (mode) {
                ProvisioningMode.EMIT -> {
                    val layout = OutputLayout(parsed.outputDir).also { it.ensureBaseDirectories() }
                    provisioner.emit(plan, layout.provisioningGg8)
                }
                ProvisioningMode.APPLY -> provisioner.apply(plan)
                ProvisioningMode.SKIP -> error("unreachable")
            }
            if (!outcome.ok) {
                throw MisconfigurationException(
                    "Gg8 provisioning ($mode) failed:\n" +
                    outcome.errors.joinToString(separator = "\n  - ", prefix = "  - ")
                )
            }
            logger.lifecycle("gg8 provisioning ($mode) ok: artifacts=${outcome.artifactsWritten.size} " +
                "created=${outcome.cachesOrTablesCreated.size} existed=${outcome.cachesOrTablesAlreadyExisted.size}")
        }

        val target = Gg8KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg8) failed: ${e.message}", e)
        exitProcess(1)
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :data-generator-gg8:compileKotlin :data-generator-gg8:test  # expect BUILD SUCCESSFUL
git add data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg8Main.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg8): Gg8Main invokes Gg8XmlProvisioner per scenario.provisioning (Plan 9 Task 9)

Pre-run hook: build ProvisioningPlan, dispatch emit/apply/skip.
ScenarioRunnerCli stays GG-agnostic. ProvisioningOutcome.errors fail-fast
with rich MisconfigurationException.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: GG9 — `Gg9SqlDdlRenderer`

**Files:**
- Create: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlDdlRenderer.kt`
- Test: `data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlDdlRendererTest.kt`

Output: a single CREATE ZONE (shared `gg_demo_zone`), then one CREATE TABLE per descriptor with optional `COLOCATE BY (...)`. SqlType: `BIGINT` → `BIGINT`; `VARCHAR` → `VARCHAR(256)`. GG9 requires the affinity column be part of the primary key for COLOCATE BY to validate — the renderer appends it to the PK clause.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg9SqlDdlRendererTest {
    private val renderer = Gg9SqlDdlRenderer()

    @Test fun `plain table no affinity`() {
        val plan = ProvisioningPlan(listOf(SchemaDescriptor("customer", "id", null, listOf(
            ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false),
            ColumnDescriptor("name", SqlType.VARCHAR, isKey = false, isAffinity = false),
        ), false)))
        assertThat(renderer.render(plan)).isEqualTo(
            """
            CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';
            CREATE TABLE IF NOT EXISTS customer (
                id BIGINT NOT NULL,
                name VARCHAR(256),
                PRIMARY KEY (id)
            ) ZONE gg_demo_zone;
            """.trimIndent()
        )
    }

    @Test fun `table with COLOCATE BY when affinity column set`() {
        val plan = ProvisioningPlan(listOf(SchemaDescriptor("order", "id", "customer_id", listOf(
            ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
            ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false),
        ), true)))
        assertThat(renderer.render(plan)).isEqualTo(
            """
            CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';
            CREATE TABLE IF NOT EXISTS order (
                customer_id BIGINT NOT NULL,
                id VARCHAR(256) NOT NULL,
                PRIMARY KEY (id, customer_id)
            ) ZONE gg_demo_zone COLOCATE BY (customer_id);
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-gg9:test --tests '*Gg9SqlDdlRendererTest'`

- [ ] **Step 3: Create the renderer**

```kotlin
package com.gridgain.demo.datagen.provisioning

/**
 * Pure renderer: ProvisioningPlan -> multi-statement SQL DDL string.
 *
 * Single shared `gg_demo_zone` (extensible per future plan). One CREATE TABLE per descriptor.
 * SqlType.BIGINT -> BIGINT; SqlType.VARCHAR -> VARCHAR(256) (length is a portability default).
 *
 * GG9 requires the affinity column to be part of the primary key for COLOCATE BY — appended
 * to PK when set. Identifier quoting is NOT applied (Plan 9 v1); future plan should add it
 * if data.yaml ever carries reserved-word column names.
 */
class Gg9SqlDdlRenderer {
    fun render(plan: ProvisioningPlan): String = buildString {
        append("CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';\n")
        plan.descriptors.forEachIndexed { i, d ->
            if (i > 0) append('\n')
            append(renderTable(d))
        }
    }.trimEnd('\n')

    private fun renderTable(d: SchemaDescriptor): String = buildString {
        append("CREATE TABLE IF NOT EXISTS ${d.schemaName} (\n")
        d.columns.forEach { col ->
            val sqlType = when (col.type) { SqlType.BIGINT -> "BIGINT"; SqlType.VARCHAR -> "VARCHAR(256)" }
            val nullable = if (col.isKey || col.isAffinity) " NOT NULL" else ""
            append("    ${col.name} $sqlType$nullable,\n")
        }
        val pkCols = buildList {
            add(d.keyColumn)
            if (d.affinityColumn != null && d.affinityColumn != d.keyColumn) add(d.affinityColumn)
        }
        append("    PRIMARY KEY (${pkCols.joinToString(", ")})\n")
        append(") ZONE gg_demo_zone")
        if (d.affinityColumn != null) append(" COLOCATE BY (${d.affinityColumn})")
        append(";")
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :data-generator-gg9:test --tests '*Gg9SqlDdlRendererTest'  # expect 2/2 pass
git add data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlDdlRenderer.kt \
        data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlDdlRendererTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg9): Gg9SqlDdlRenderer (Plan 9 Task 10)

Pure renderer: CREATE ZONE + one CREATE TABLE per descriptor with
optional COLOCATE BY. Affinity column appended to PRIMARY KEY (GG9
requires that for COLOCATE BY to validate).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: GG9 — `Gg9SqlProvisioner.emit`

**Files:**
- Create: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisioner.kt`
- Test: `data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisionerEmitTest.kt`

Single `ddl.sql` (one file, all statements). Single file is preferable to one-per-schema because the zone declaration is shared.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class Gg9SqlProvisionerEmitTest {
    private val provisioner = Gg9SqlProvisioner(clusterName = "unused-for-emit")

    @Test fun `emit writes one ddl_sql file`(@TempDir dest: Path) {
        val plan = ProvisioningPlan(listOf(
            SchemaDescriptor("customer", "id", null,
                listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false),
            SchemaDescriptor("order", "id", "customer_id", listOf(
                ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false),
            ), true),
        ))
        val outcome = provisioner.emit(plan, dest)
        assertThat(outcome.errors).isEmpty()
        assertThat(outcome.artifactsWritten).containsExactly(dest.resolve("ddl.sql"))
        val ddl = Files.readString(dest.resolve("ddl.sql"))
        assertThat(ddl)
            .startsWith("CREATE ZONE IF NOT EXISTS gg_demo_zone")
            .contains("CREATE TABLE IF NOT EXISTS customer")
            .contains("CREATE TABLE IF NOT EXISTS order")
            .contains("COLOCATE BY (customer_id)")
    }
}
```

- [ ] **Step 2: Run + verify FAIL** — `./gradlew :data-generator-gg9:test --tests '*Gg9SqlProvisionerEmitTest'`

- [ ] **Step 3: Create `Gg9SqlProvisioner.kt` (emit only)**

```kotlin
package com.gridgain.demo.datagen.provisioning

import java.nio.file.Files
import java.nio.file.Path

class Gg9SqlProvisioner(
    private val clusterName: String,
    private val renderer: Gg9SqlDdlRenderer = Gg9SqlDdlRenderer(),
) : Provisioner {

    override fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome {
        if (!Files.isDirectory(destinationDir)) Files.createDirectories(destinationDir)
        return try {
            val path = destinationDir.resolve("ddl.sql")
            Files.writeString(path, renderer.render(plan))
            ProvisioningOutcome(listOf(path), emptyList(), emptyList(), emptyList())
        } catch (e: Exception) {
            ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg9SqlProvisioner.emit failed writing ${destinationDir.resolve("ddl.sql")}: ${e.message}. " +
                "Verify the destination is writable."
            ))
        }
    }

    override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
        // Implemented in Task 12.
        throw NotImplementedError("Gg9SqlProvisioner.apply lands in Plan 9 Task 12")
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :data-generator-gg9:test --tests 'com.gridgain.demo.datagen.provisioning.Gg9SqlProvisionerEmitTest'
git add data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisioner.kt \
        data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisionerEmitTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg9): Gg9SqlProvisioner.emit writes ddl.sql (Plan 9 Task 11)

Single ddl.sql holds CREATE ZONE plus one CREATE TABLE per schema.
apply() lands in Task 12.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: GG9 — `Gg9SqlProvisioner.apply`

**Files:**
- Modify: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisioner.kt`
- Test: `data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisionerApplyTest.kt` (env-gated)

Opens GG9 `IgniteClient` (same shape as `Gg9KvTarget.ensureClient`), splits the rendered DDL on `;\n` (or `;`), and runs each statement via `client.sql().execute(null as Transaction?, stmt).close()`. Idempotency comes from `IF NOT EXISTS`.

- [ ] **Step 1: Write the failing env-gated test**

```kotlin
package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9SqlProvisionerApplyTest {
    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test_provisioned"

    @Test fun `apply creates table; second run is a no-op`() {
        val provisioner = Gg9SqlProvisioner(clusterName)
        val plan = ProvisioningPlan(listOf(SchemaDescriptor(
            schemaName = tableName, keyColumn = "id", affinityColumn = null,
            columns = listOf(
                ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false),
                ColumnDescriptor("name", SqlType.VARCHAR, isKey = false, isAffinity = false),
            ), transactional = false)))
        val first = provisioner.apply(plan)
        assertThat(first.errors).isEmpty()
        val second = provisioner.apply(plan)
        assertThat(second.errors).isEmpty()
        // GG9 IF NOT EXISTS does not surface "already existed" through SQL — both runs report
        // optimistically as "created". We assert no errors and leave count semantics looser than GG8.
    }
}
```

- [ ] **Step 2: Run + verify SKIPPED** — `./gradlew :data-generator-gg9:test --tests '*Gg9SqlProvisionerApplyTest'` (without `DATAGEN_GG9_CLUSTER_NAME`).

- [ ] **Step 3: Implement `apply`**

In `Gg9SqlProvisioner.kt`, add imports at the top:

```kotlin
import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.tx.Transaction
```

Replace the `apply` method body:

```kotlin
override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
    val ddl = renderer.render(plan)
    val statements = ddl.split(";\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
    val errors = mutableListOf<String>()
    val created = mutableListOf<String>()

    val client: IgniteClient = try {
        IgniteClient.builder().addressFinder(DemoAddressFinder(clusterName)).build()
    } catch (e: Exception) {
        return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
            "Gg9SqlProvisioner.apply could not connect to GG9 cluster '$clusterName': ${e.message}. " +
            "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
            "and the cluster name matches the clusters[].name entry."
        ))
    }
    client.use { ignite ->
        val sql = ignite.sql()
        for (stmt in statements) {
            try {
                sql.execute(null as Transaction?, stmt).close()
            } catch (e: Exception) {
                errors += "Gg9SqlProvisioner.apply failed executing DDL [$stmt]: ${e.message}. " +
                    "If the table or zone already exists with a different definition, drop it manually " +
                    "and retry, or align data.yaml. IF NOT EXISTS guards prevent duplicate-create errors " +
                    "but cannot reconcile mismatched columns."
            }
        }
        if (errors.isEmpty()) created += plan.descriptors.map { it.schemaName }
    }
    return ProvisioningOutcome(emptyList(), created, emptyList(), errors)
}
```

- [ ] **Step 4: Run env-gated test (when cluster available) + commit**

```bash
DATAGEN_GG9_CLUSTER_NAME=<cluster> DATAGEN_GG9_TEST_TABLE=data_gen_test_provisioned \
GG_DEMO_CLIENT_ENDPOINTS=<absolute path> \
  ./gradlew :data-generator-gg9:test --tests '*Gg9SqlProvisionerApplyTest'
# expect: errors=[] in both runs; without env vars, test silently skips.

git add data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisioner.kt \
        data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/provisioning/Gg9SqlProvisionerApplyTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg9): Gg9SqlProvisioner.apply via client.sql() (Plan 9 Task 12)

Idempotent through IF NOT EXISTS clauses. Splits multi-statement DDL on
semicolon-newline; runs each through client.sql().execute(null, stmt).
Mismatch errors carry rich remediation guidance.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: GG9 — wire `Gg9Main`

**Files:**
- Modify: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg9Main.kt`

Mirror Task 9 with three substitutions in the new file:
- `@file:JvmName("Gg9Main")`
- `Gg9KvTargetSpec` instead of `Gg8KvTargetSpec` (mismatched-spec error message says `gg9-kv` and suggests `Gg8Main`).
- `Gg9SqlProvisioner` constructed instead of `Gg8XmlProvisioner`; `provisioner.emit(plan, layout.provisioningGg9)` instead of `provisioningGg8`.
- `Gg9KvTarget(...)` instead of `Gg8KvTarget(...)`.
- Lifecycle/log messages say `gg9` instead of `gg8`.

Everything else (the `mode` switch, the `outcome.ok` guard, the `ScenarioRunnerCli.run(...)` delegation, `exitProcess`) is byte-for-byte identical.

- [ ] **Step 1: Edit `Gg9Main.kt`** — apply the substitutions above to the body shown in Task 9.

- [ ] **Step 2: Compile + test + commit**

```bash
./gradlew :data-generator-gg9:compileKotlin :data-generator-gg9:test
git add data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg9Main.kt
git commit -m "$(cat <<'EOF'
feat(datagen-gg9): Gg9Main invokes Gg9SqlProvisioner per scenario.provisioning (Plan 9 Task 13)

Mirrors Gg8Main (Task 9). EMIT writes ddl.sql under provisioningGg9;
APPLY executes the same DDL via client.sql(); SKIP bypasses.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: ROADMAP update — Plan 9 entry, close F6, open F10

**Files:**
- Modify: `docs/superpowers/ROADMAP.md`

- [ ] **Step 1: Edit `ROADMAP.md`**

Four edits:

1. **Current State** — bump test count (~162 across modules); append: "After Plan 9 every scenario carries `provisioning: skip|emit|apply`; the data generator can render GG8 cache XML, GG9 SQL DDL, and create absent caches/tables idempotently. The `affinity: true` annotation is now consumed."

2. Move F6 from **Open Follow-ups** to a new **Closed Follow-ups** subsection:

```
### F6 — TRANSACTIONAL cache provisioning ✅ *(closed by Plan 9)*
Plan 9 Task 8 sets CacheAtomicityMode.TRANSACTIONAL on caches whose
descriptors carry transactional = true (driven by transaction_scope:
business_event). Gg8KvTarget.putRow keeps using getOrCreateCache(name)
because the cache now exists with the right mode.
```

3. Append a new **Open Follow-ups** entry:

```
### F10 — Provisioning SqlType inference's coarse defaults
*Source: Plan 9 review.*
ProvisioningPlanFactory.inferType maps every non-SequenceSpec value source
to SqlType.VARCHAR, including WeightedChoiceSpec whose choices may be
numeric. Gg9SqlDdlRenderer widens VARCHAR to VARCHAR(256). Both are safe
defaults but a future plan should:
(a) infer from the runtime type of WeightedChoiceSpec.choices[0].value,
(b) parameterize VARCHAR length per column,
(c) extend SqlType to cover timestamp / decimal / numeric.
```

4. Add a Plan 9 entry next to Plan 7.5 in the "complete plans" list:

```
## Plan 9 — Provisioning Emit + Apply *(complete)*

Per spec §4. Adds per-scenario `provisioning: skip|emit|apply`. emit
writes GG8 cache XML to <outputDir>/data-generator/provisioning/gg8/ and
GG9 SQL DDL to .../provisioning/gg9/. apply creates absent caches/tables
on the cluster idempotently. The `affinity: true` annotation is finally
consumed (GG8 keyConfiguration; GG9 COLOCATE BY). Closes F6 (TRANSACTIONAL
cache mode); opens F10 (SqlType inference refinement).

All 14 tasks done — see `plans/2026-05-03-data-generator-plan-9-provisioning.md`.
```

Bump **Last updated** line: `2026-05-03 (after Plan 9 — provisioning emit + apply)`.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/ROADMAP.md
git commit -m "$(cat <<'EOF'
docs(datagen): roadmap — Plan 9 complete; F6 closed; F10 opened (Plan 9 Task 14)

Records Plan 9 as complete. Marks F6 (TRANSACTIONAL cache provisioning)
as closed. Opens F10 (SqlType inference refinement).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Final verification

- [ ] **Step 1: Full clean build + test**

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL across all three subprojects (~162 tests; env-gated integration tests skip).

- [ ] **Step 2: Live-cluster smoke (when available)**

Add `provisioning: apply` to a TaxiDemo scenario, then:

```bash
cd ../TaxiDemo
./gradlew dataGenerate --scenario customer-load
```

Expected: run completes, `result.yaml` shows `success_count > 0`, the cluster has the expected caches with the expected `atomicityMode` and `keyConfiguration`. If no cluster is up, document in the report.

- [ ] **Step 3: Visual review checklist**

- `data-generator-core/.../provisioning/` carries 7 files (`SqlType`, `ColumnDescriptor`, `SchemaDescriptor`, `ProvisioningPlan`, `ProvisioningOutcome`, `Provisioner`, `ProvisioningPlanFactory`).
- `data-generator-gg8/.../provisioning/` carries `Gg8CacheXmlRenderer.kt` + `Gg8XmlProvisioner.kt`.
- `data-generator-gg9/.../provisioning/` carries `Gg9SqlDdlRenderer.kt` + `Gg9SqlProvisioner.kt`.
- `Gg8Main` / `Gg9Main` invoke the provisioner before constructing the runtime `Target`.
- `ScenarioRunnerCli.kt` has no `Provisioner` import — orchestration lives in per-flavor `Main`.
- `ColumnSpec.affinity` is now read by code (`ProvisioningPlanFactory` + renderers), not just parsed-and-ignored.
- `OpsConfigMigrationRunner` is unchanged — `provisioning` is additive at v2 with a default.

- [ ] **Step 4: Run `superpowers:requesting-code-review`** (subagent-driven step)

---

## Self-review

- [ ] All commit messages end with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- [ ] No `org.gradle.*` or `org.apache.ignite.*` imports in `data-generator-core`.
- [ ] `Provisioner` is a plain interface (not sealed) — impls live in different gradle modules.
- [ ] All `ProvisioningOutcome.errors` carry rich remediation messages; no silent fallbacks.
- [ ] Only one new nullable (`SchemaDescriptor.affinityColumn: String?`); documented.
- [ ] No deprecation annotations; SnakeYAML stays at 1.33.
- [ ] Plan 9 closes F6 (Task 8); ROADMAP updated (Task 14).

## Spec coverage audit

| Spec § | Tasks |
|---|---|
| §4 `provisioning: skip\|emit\|apply` field, default skip | 1 |
| §4 emit writes under `<outputDir>/data-generator/provisioning/` | 2, 7, 11 |
| §4 GG8 cache XML with affinityKey + atomicityMode | 6, 7 |
| §4 GG9 SQL DDL `CREATE ZONE` / `TABLE` / `COLOCATE BY` | 10, 11 |
| §4 K8s YAML | **DEFERRED** (out of scope for cache/table provisioning) |
| §4 apply creates absent caches/tables idempotently; mismatch fails with remediation | 8, 12 |
| §4 skip assumes pre-existence | already in Plans 6 + 7 |
| §1 `affinity: true` annotation consumed | 4, 6, 10 |
| F6 TRANSACTIONAL cache provisioning | 8 (closes it) |

**Out of scope:** K8s YAML emit; F1–F4 + F7 follow-ups; Plan 10 (state); Plan 11 (OTel); F10 (SqlType refinement, opened here); drop/recreate semantics (spec §4 explicit out-of-scope).

## Critical files (forward references)

- `data-generator-core/.../provisioning/ProvisioningPlanFactory.kt` — the GG-agnostic builder; Plan 10 likely reuses the descriptor list.
- `data-generator-gg{8,9}/.../provisioning/Gg{8XmlProvisioner,9SqlProvisioner}.kt` — per-flavor entry points; future SQL-target plans add siblings sharing the same `ProvisioningPlan`.
- `data-generator-core/.../provisioning/SqlType.kt` — extension point for additional column types (F10).
