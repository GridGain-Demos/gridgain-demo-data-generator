# Data Generator — Plan 6: GG8 KV Target

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the data generator to a real GG8 cluster via the existing `gg8-client-finder` plumbing. Implement the `Gg8KvTarget` (KV writes with optional transaction wrapping; reads), per-schema `KeyRegistry`, `update_ratio` execution at the scenario-runner level, and read execution. Add the `key: true` column annotation and the typed `targets:` block in `ops.yaml` so scenarios can name a specific target. Ship integration tests gated by environment variables so the suite stays green when no cluster is available.

**Architecture:** The data generator declares maven-local dependencies on `com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT` (transitively pulls `client-finder-common`) and `org.gridgain:ignite-core:8.9.18`. `Gg8KvTarget(clusterName, supportsTransactions)` lazily opens an `IgniteClient` via `DemoAddressFinder(clusterName)`; on `write(event)` it puts the parent's key/value pair to the cache named after the parent schema, then iterates `childrenBySchema` doing the same for each child row. When `transaction_scope: business_event`, the entire subtree is wrapped in `client.transactions().txStart()` … `commit()`. `read(cacheName, keyValue)` does `client.cache(name).get(key)`. The `KeyRegistry` is a per-scenario, per-schema `MutableSet<Any>` of emitted key values. The `ScenarioRunner` decides read-vs-write per tick (`random < read_ratio` → read), then for writes decides update-vs-insert per root schema (`random < update_ratio AND registry has keys` → swap the generated event's key column with a registry sample). Integration tests are annotated `@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".*")` so they no-op without a cluster.

**Tech Stack additions:**
- `com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT` (must be `publishToMavenLocal`'d first from `gridgain-demo-client-utils`)
- `org.gridgain:ignite-core:8.9.18`
- GridGain external maven repo for transitive ignite jars

**Pre-execution prerequisites (operator must satisfy before Task 1):**
1. `cd ../gridgain-demo-client-utils && ./gradlew publishToMavenLocal`
2. A reachable GG8 cluster (single-node Docker locally OR plugin-deployed in GCP)
3. A `client-endpoints.yaml` file with a `clusters[]` entry naming this cluster, surfaced via the `gridgain-demo-client-utils` resolution mechanism (env var or default path)
4. Two env vars exported in the shell that runs integration tests:
   - `DATAGEN_GG8_CLUSTER_NAME=<the cluster name>`
   - `DATAGEN_GG8_TEST_CACHE=<a writable cache name in that cluster, e.g. `data_gen_test`>`

---

## Spec extensions to v2

```yaml
# data.yaml — additive: key flag on a column
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.20
    columns:
      - name: id
        null_rate: 0.0
        key: true                 # <-- new optional field, defaults to false
        affinity: true
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: name
        null_rate: 0.02
        value_source: { kind: datafaker, expression: "#{name.fullName}" }

# ops.yaml — typed targets[] + scenario.target
schema_version: 2
targets:
  - name: gg8-trip-cluster
    kind: gg8-kv
    cluster_name: trip-cluster
scenarios:
  - name: customer-load
    target: gg8-trip-cluster
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 50 }
    duration: { kind: time, value: PT15S }
    transaction_scope: business_event
    read_ratio: 0.10
```

The `cluster_name` references an entry in `client-endpoints.yaml` per the existing `gg8-client-finder` contract.

---

## File Structure

```
gridgain-demo-data-generator/
├── build.gradle.kts                    # add maven repos, gg8 deps
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── DataConfig.kt               # add key: Boolean = false on ColumnSpec
│   │   ├── OpsConfig.kt                # add targets: List<TargetSpec>
│   │   ├── TargetSpec.kt               # NEW: sealed TargetSpec, Gg8KvTargetSpec
│   │   └── CrossElementValidator.kt    # add KeyColumnValidator, ScenarioTargetValidator
│   ├── target/
│   │   ├── Target.kt                   # add read(cacheName, key)
│   │   ├── ReadOutcome.kt              # NEW
│   │   ├── InMemoryTarget.kt           # impl new read(); track stored map
│   │   └── Gg8KvTarget.kt              # NEW
│   └── scenario/
│       ├── KeyRegistry.kt              # NEW
│       └── ScenarioRunner.kt           # integrate registry + read/write/update mix
└── src/main/resources/schema/
    ├── data/v2.schema.json             # add key: boolean
    └── ops/v2.schema.json              # add targets[] + scenario.target
```

---

### Task 1: Build wiring — maven repos, gg8 deps

**Files:**
- Modify: `build.gradle.kts`

Add the GridGain external maven repo and `mavenLocal()`. Add `implementation` dependencies on `gg8-client-finder` (transitively pulls `client-finder-common`) and `ignite-core`.

- [ ] **Step 1: Replace `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.gridgain.demo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "GridGain External Repository"
        url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
    }
}

dependencies {
    implementation("net.datafaker:datafaker:2.5.4")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    implementation("com.networknt:json-schema-validator:1.5.9")

    implementation("org.slf4j:slf4j-api:2.0.13")

    // Plan 6 — GG8 KV target
    implementation("com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT")
    implementation("org.gridgain:ignite-core:8.9.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

configurations.all {
    resolutionStrategy {
        force("org.yaml:snakeyaml:1.33")
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 2: Verify dependency resolution**

`./gradlew --no-daemon build -x test`

Expected: `BUILD SUCCESSFUL`. If `com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT` is unresolved, run `cd ../gridgain-demo-client-utils && ./gradlew publishToMavenLocal` and retry.

- [ ] **Step 3: Verify the suite still passes**

`./gradlew test` — all 121 prior tests still pass.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "chore(datagen): add maven repos and gg8-client-finder + ignite-core deps"
```

Sign with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

### Task 2: Add `key: true` column annotation

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`
- Modify: `src/main/resources/schema/data/v2.schema.json`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/KeyFieldDeserializationTest.kt`

Mirrors Plan 5 Task 1's `affinity` field — additive, defaults to `false`. Same documented exception to the workspace "no defaults" rule.

- [ ] **Step 1: Update ColumnSpec**

Replace `ColumnSpec` in `DataConfig.kt`:

```kotlin
data class ColumnSpec(
    val name: String,
    @JsonProperty("null_rate") val nullRate: Double,
    val affinity: Boolean = false,
    val key: Boolean = false,
    @JsonProperty("value_source") val valueSource: ValueSourceSpec,
)
```

- [ ] **Step 2: Update v2 JSONSchema**

In `src/main/resources/schema/data/v2.schema.json`, in `$defs.column.properties`, add:

```json
"key": { "type": "boolean" }
```

Do NOT add to required.

- [ ] **Step 3: Write deserialization test**

`src/test/kotlin/com/gridgain/demo/datagen/config/KeyFieldDeserializationTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class KeyFieldDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `key defaults to false when omitted`() {
        val yaml = """
            name: id
            null_rate: 0.0
            value_source: { kind: sequence, start: 1, step: 1 }
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.key).isFalse()
    }

    @Test
    fun `key is read when present`() {
        val yaml = """
            name: id
            null_rate: 0.0
            key: true
            value_source: { kind: sequence, start: 1, step: 1 }
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.key).isTrue()
    }
}
```

- [ ] **Step 4: Run targeted + full suite**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.KeyFieldDeserializationTest'` — 2 PASS.
`./gradlew test` — full suite green, 123 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt src/main/resources/schema/data/v2.schema.json src/test/kotlin/com/gridgain/demo/datagen/config/KeyFieldDeserializationTest.kt
git commit -m "feat(datagen): add optional key flag to ColumnSpec"
```

---

### Task 3: KeyColumnValidator (cross-element)

**Files:**
- Modify (append): `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt` (compose)
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/KeyColumnValidatorTest.kt`

Each schema must have **exactly one** column with `key: true`. Zero is an error; two or more is an error.

- [ ] **Step 1: Write tests**

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class KeyColumnValidatorTest {

    private fun col(name: String, isKey: Boolean = false) = ColumnSpec(
        name = name, nullRate = 0.0, key = isKey,
        valueSource = SequenceSpec(1, 1),
    )

    @Test
    fun `accepts a schema with exactly one key column`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("id", isKey = true), col("name")))))
        assertThat(KeyColumnValidator().validate(data, OpsConfig(2, emptyList())).errors).isEmpty()
    }

    @Test
    fun `rejects a schema with no key column`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("id"), col("name")))))
        val r = KeyColumnValidator().validate(data, OpsConfig(2, emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("no key column")
    }

    @Test
    fun `rejects a schema with multiple key columns`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("a", isKey = true), col("b", isKey = true)))))
        val r = KeyColumnValidator().validate(data, OpsConfig(2, emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("more than one key column")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Append validator to CrossElementValidator.kt**

```kotlin
class KeyColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            val keyColumns = schema.columns.filter { it.key }
            when (keyColumns.size) {
                0 -> errors += "schema '${schema.name}' has no key column. " +
                    "Mark exactly one column with 'key: true'."
                1 -> Unit
                else -> errors += "schema '${schema.name}' has more than one key column " +
                    "(${keyColumns.joinToString(", ") { it.name }}). " +
                    "Mark exactly one column with 'key: true'."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
```

- [ ] **Step 4: Compose into parser default**

Add `KeyColumnValidator()` to the `CompositeCrossElementValidator(listOf(...))` list in `ConfigurationParser.kt`.

- [ ] **Step 5: Update existing test fixtures that lack key columns**

This change introduces a new validation requirement that may fail existing fixtures (e.g., `data-v2-customer.yaml`, `data-v2-customer-order.yaml`). Update both fixtures to mark the `id` column with `key: true`. Same for any inline test yaml strings or constructor calls in test files.

Likely test files needing updates:
- `src/test/resources/data-v2-customer.yaml` — mark `id` with `key: true`
- `src/test/resources/data-v2-customer-order.yaml` — mark `customer.id` with `key: true`; for `order` schema, mark whichever column is intended as key (likely `id` since it's the key-suffix-derived one)
- Any test in `src/test/kotlin/.../config/` that constructs `SchemaSpec` inline — add a `key = true` column

Run `./gradlew test` to find every failure; fix systematically.

- [ ] **Step 6: Run full suite**

`./gradlew test` — `BUILD SUCCESSFUL`, 126 tests (123 prior + 3 new).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(datagen): add KeyColumnValidator and mark key columns in fixtures"
```

---

### Task 4: TargetSpec sealed hierarchy + ops v2 typed targets

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/TargetSpec.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt`
- Modify: `src/main/resources/schema/ops/v2.schema.json`

`OpsConfig` gains a `targets: List<TargetSpec>` field. `ScenarioSpec` gains a `target: String` field referencing a target name. `TargetSpec` is sealed; the only initial subtype is `Gg8KvTargetSpec(name, clusterName)`.

- [ ] **Step 1: Create TargetSpec hierarchy**

`src/main/kotlin/com/gridgain/demo/datagen/config/TargetSpec.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Gg8KvTargetSpec::class, name = "gg8-kv"),
)
sealed class TargetSpec {
    abstract val name: String
}

data class Gg8KvTargetSpec(
    override val name: String,
    @JsonProperty("cluster_name") val clusterName: String,
) : TargetSpec()
```

- [ ] **Step 2: Update OpsConfig**

`src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val targets: List<TargetSpec> = emptyList(),
    val scenarios: List<ScenarioSpec>,
)
```

(The `targets` default is required to avoid breaking every existing test that constructs `OpsConfig(2, listOf(...))` without targets. Document the exception in the data class.)

- [ ] **Step 3: Update ScenarioSpec**

In `ScenarioSpec.kt`, add a `target: String` field:

```kotlin
data class ScenarioSpec(
    val name: String,
    val target: String = "",   // exception: defaults empty for back-compat with existing tests
    @JsonProperty("root_schemas") val rootSchemas: List<String>,
    val rate: RateSpec,
    val duration: DurationSpec,
    @JsonProperty("stop_conditions") val stopConditions: List<StopConditionSpec> = emptyList(),
    @JsonProperty("transaction_scope") val transactionScope: TransactionScope,
    @JsonProperty("read_ratio") val readRatio: Double,
)
```

(The `target = ""` default is another back-compat exception. The `ScenarioTargetValidator` in Task 5 enforces non-empty `target` when the rest of the config requires it.)

- [ ] **Step 4: Update v2 ops JSONSchema**

In `src/main/resources/schema/ops/v2.schema.json`:

1. Top-level `properties`, add `targets`:

```json
"targets": { "type": "array", "items": { "$ref": "#/$defs/target" } }
```

2. Top-level `required`: add `targets` after `scenarios`. Actually, KEEP `targets` optional (defaults to empty); only `schema_version` and `scenarios` remain required. So leave `required` unchanged but add the optional `targets` property.

3. In `$defs.scenario.properties`, add `target`:

```json
"target": { "type": "string" }
```

Don't add to `$defs.scenario.required` — keep optional.

4. In `$defs`, add:

```json
"target": {
  "oneOf": [ { "$ref": "#/$defs/target_gg8_kv" } ]
},
"target_gg8_kv": {
  "type": "object",
  "required": ["kind", "name", "cluster_name"],
  "additionalProperties": false,
  "properties": {
    "kind": { "const": "gg8-kv" },
    "name": { "type": "string", "minLength": 1 },
    "cluster_name": { "type": "string", "minLength": 1 }
  }
}
```

- [ ] **Step 5: Verify compile + suite**

`./gradlew compileKotlin` — succeeds.
`./gradlew test` — green. Update test fixtures or constructor calls if they break (similar to Task 3 Step 5).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(datagen): add TargetSpec hierarchy, targets[] in OpsConfig, target on ScenarioSpec"
```

---

### Task 5: ScenarioTargetValidator + extend ScenarioRootSchemaValidator with target reference

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt` (compose)
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioTargetValidatorTest.kt`

A scenario's `target` (when non-empty) must reference a declared target. Capability compatibility:
- `read_ratio > 0` requires the target's `supportsReads = true`
- `transaction_scope: business_event` requires `supportsTransactions = true`

Per-target `supportsReads` / `supportsTransactions` are looked up from a static map keyed by spec class. For `Gg8KvTargetSpec`: `supportsReads = true`, `supportsTransactions = true`.

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioTargetValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ScenarioTargetValidatorTest {

    private fun col() = ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
    private fun data() = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
    private fun gg8(name: String) = Gg8KvTargetSpec(name = name, clusterName = "trip")

    private fun scenario(name: String, target: String, readRatio: Double = 0.0,
                         tx: TransactionScope = TransactionScope.NONE) = ScenarioSpec(
        name = name, target = target, rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0), duration = TimeDurationSpec("PT1S"),
        transactionScope = tx, readRatio = readRatio,
    )

    @Test
    fun `accepts a scenario referencing a declared target`() {
        val ops = OpsConfig(2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", "gg8-trip")))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `rejects a scenario with empty target`() {
        val ops = OpsConfig(2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", target = "")))
        val r = ScenarioTargetValidator().validate(data(), ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("scenario 's'").contains("target")
    }

    @Test
    fun `rejects a scenario referencing an undeclared target`() {
        val ops = OpsConfig(2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", "missing")))
        val r = ScenarioTargetValidator().validate(data(), ops)
        assertThat(r.errors[0]).contains("missing").contains("not declared")
    }

    @Test
    fun `Gg8 target supports reads — read_ratio gt 0 accepted`() {
        val ops = OpsConfig(2, targets = listOf(gg8("gg8-trip")),
            scenarios = listOf(scenario("s", "gg8-trip", readRatio = 0.5)))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `Gg8 target supports transactions — business_event accepted`() {
        val ops = OpsConfig(2, targets = listOf(gg8("gg8-trip")),
            scenarios = listOf(scenario("s", "gg8-trip", tx = TransactionScope.BUSINESS_EVENT)))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Append validator**

Append to `CrossElementValidator.kt`:

```kotlin
class ScenarioTargetValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val targetsByName = ops.targets.associateBy { it.name }
        for (scenario in ops.scenarios) {
            if (scenario.target.isBlank()) {
                errors += "scenario '${scenario.name}' has no target. " +
                    "Add a 'target: <name>' field referencing one of the targets declared in ops.yaml."
                continue
            }
            val target = targetsByName[scenario.target]
            if (target == null) {
                errors += "scenario '${scenario.name}' references target '${scenario.target}' " +
                    "which is not declared in ops.yaml. " +
                    "Available targets: ${targetsByName.keys.joinToString(", ").ifBlank { "(none)" }}."
                continue
            }
            val (supportsReads, supportsTransactions) = capabilitiesFor(target)
            if (scenario.readRatio > 0.0 && !supportsReads) {
                errors += "scenario '${scenario.name}' has read_ratio ${scenario.readRatio} " +
                    "but target '${scenario.target}' does not support reads. " +
                    "Set read_ratio to 0.0 or use a target that supports reads."
            }
            if (scenario.transactionScope == TransactionScope.BUSINESS_EVENT && !supportsTransactions) {
                errors += "scenario '${scenario.name}' has transaction_scope: business_event " +
                    "but target '${scenario.target}' does not support transactions. " +
                    "Set transaction_scope to 'none' or use a target that supports transactions."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }

    private fun capabilitiesFor(target: TargetSpec): Pair<Boolean, Boolean> = when (target) {
        is Gg8KvTargetSpec -> true to true
    }
}
```

- [ ] **Step 4: Compose into parser default**

Add `ScenarioTargetValidator()` to the composite list.

- [ ] **Step 5: Run full suite**

`./gradlew test` — green. Update existing tests that have scenarios without `target = "<name>"` AND `transaction_scope: business_event` (they'll trip the new validator). Either fill in a fake target in the OpsConfig or change the scenario's `transaction_scope` to NONE.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(datagen): add ScenarioTargetValidator for target reference + capability compat"
```

---

### Task 6: Target.read() + ReadOutcome + InMemoryTarget integration

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/Target.kt`
- Create: `src/main/kotlin/com/gridgain/demo/datagen/target/ReadOutcome.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/InMemoryTarget.kt`
- Modify: `src/test/kotlin/com/gridgain/demo/datagen/target/InMemoryTargetTest.kt`

`Target` gains a `read(cacheName: String, key: Any): ReadOutcome` method. `ReadOutcome` is `(success, value, error)`. `InMemoryTarget` stores a per-cache `Map<Any, Map<...>>` keyed by the cache name + the parent row's key column. Stores on `write` for later retrieval; `read` looks up.

For Plan 6, `InMemoryTarget` doesn't yet know which column is the key — it just stores the entire `parentRow` map keyed by a synthetic key. A later refinement: when `keyColumn` is exposed, `InMemoryTarget` could index by the actual key. For now, it indexes by `event.parentRow.toString()` as an opaque key — sufficient for the existing tests that only check `t.writes`.

Actually simpler: `InMemoryTarget` doesn't need to honor reads meaningfully; just record the call and return success. ScenarioRunner will call `read(cacheName, key)` and we'll record the call. Real read semantics are exercised by `Gg8KvTarget` integration tests.

- [ ] **Step 1: Update Target interface**

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class WriteOutcome(
    val success: Boolean,
    val error: Throwable? = null,
)

data class ReadOutcome(
    val success: Boolean,
    val value: Any? = null,
    val error: Throwable? = null,
)

interface Target {
    val supportsReads: Boolean
    val supportsTransactions: Boolean
    fun write(event: BusinessEvent): WriteOutcome
    fun read(cacheName: String, key: Any): ReadOutcome
}
```

- [ ] **Step 2: Update InMemoryTarget**

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class ReadCall(val cacheName: String, val key: Any)

class InMemoryTarget : Target {
    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = false

    private val _writes: MutableList<BusinessEvent> = mutableListOf()
    val writes: List<BusinessEvent> get() = _writes

    private val _reads: MutableList<ReadCall> = mutableListOf()
    val reads: List<ReadCall> get() = _reads

    override fun write(event: BusinessEvent): WriteOutcome {
        _writes.add(event)
        return WriteOutcome(success = true)
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        _reads.add(ReadCall(cacheName, key))
        return ReadOutcome(success = true, value = "in-memory-stub")
    }
}
```

- [ ] **Step 3: Add tests for read**

Append to `src/test/kotlin/com/gridgain/demo/datagen/target/InMemoryTargetTest.kt`:

```kotlin
@Test
fun `records each read call`() {
    val t = InMemoryTarget()
    val r = t.read(cacheName = "customer", key = 42L)
    assertThat(r.success).isTrue()
    assertThat(r.value).isEqualTo("in-memory-stub")
    assertThat(t.reads).hasSize(1)
    assertThat(t.reads[0].cacheName).isEqualTo("customer")
    assertThat(t.reads[0].key).isEqualTo(42L)
}
```

- [ ] **Step 4: Run targeted + suite**

`./gradlew test --tests 'com.gridgain.demo.datagen.target.InMemoryTargetTest'` — 4 PASS.
`./gradlew test` — full green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datagen): add Target.read and ReadOutcome; InMemoryTarget records reads"
```

---

### Task 7: KeyRegistry

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistry.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistryTest.kt`

Per-schema `MutableSet<Any>` of emitted keys. API: `register(schemaName, key)`, `sample(schemaName, random): Any?` (null when empty), `size(schemaName): Int`.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.Random
import kotlin.test.Test

class KeyRegistryTest {

    @Test
    fun `sample is null when registry is empty for that schema`() {
        val r = KeyRegistry()
        assertThat(r.sample("customer", Random(1L))).isNull()
    }

    @Test
    fun `register stores keys per schema`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        r.register("order", "abc")
        assertThat(r.size("customer")).isEqualTo(2)
        assertThat(r.size("order")).isEqualTo(1)
        assertThat(r.size("missing")).isEqualTo(0)
    }

    @Test
    fun `sample returns one of the registered keys`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        r.register("customer", 3L)
        val sampled = (1..100).map { r.sample("customer", Random(it.toLong())) }.toSet()
        assertThat(sampled).isSubsetOf(1L, 2L, 3L)
        assertThat(sampled).hasSizeGreaterThan(1)
    }

    @Test
    fun `register is idempotent for duplicate keys`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 1L)
        assertThat(r.size("customer")).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.datagen.scenario

import java.util.Random

class KeyRegistry {

    private val keysBySchema: MutableMap<String, MutableList<Any>> = mutableMapOf()
    private val seenBySchema: MutableMap<String, MutableSet<Any>> = mutableMapOf()

    fun register(schemaName: String, key: Any) {
        val seen = seenBySchema.getOrPut(schemaName) { mutableSetOf() }
        if (seen.add(key)) {
            keysBySchema.getOrPut(schemaName) { mutableListOf() }.add(key)
        }
    }

    fun sample(schemaName: String, random: Random): Any? {
        val keys = keysBySchema[schemaName] ?: return null
        if (keys.isEmpty()) return null
        return keys[random.nextInt(keys.size)]
    }

    fun size(schemaName: String): Int = keysBySchema[schemaName]?.size ?: 0
}
```

- [ ] **Step 4: Run — 4 PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistry.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistryTest.kt
git commit -m "feat(datagen): add per-schema KeyRegistry for update_ratio + reads"
```

---

### Task 8: ScenarioRunner — wire KeyRegistry, update_ratio, read_ratio

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerKvSemanticsTest.kt`

The runner now:
1. On each tick, decide read vs write: `random < scenario.readRatio AND target.supportsReads AND registry has any key for the root schema`.
2. For reads: pick a random root schema, sample a key from the registry, call `target.read(cacheName = rootSchemaName, key = sampledKey)`. Record success/failure + latency.
3. For writes:
   - Generate the event.
   - For the root schema: if `random < rootSchema.updateRatio AND registry has at least one key`, swap the parent row's key-column value with a registry sample (this is now an "update").
   - Else: register the new key (this is an "insert").
   - Call `target.write(event)`. Record success/failure + latency.
4. Need to know which column is the key column for each schema — look it up from `data.schemas` via `column.key == true`.

Constructor change: ScenarioRunner now needs the `DataConfig` (to look up schemas + find key columns + get update_ratio). Add it as a parameter.

- [ ] **Step 1: Update ScenarioRunner**

The full updated file is too long to inline here without losing readability. Critical changes vs Plan 5:
- New constructor parameters: `data: DataConfig`, `decisionRandom: Random = Random()`
- New private fields: `keyRegistry: KeyRegistry`, key-column-name map per root schema
- New `private fun tick()` that does:
  1. `rateLimiter.acquire()`
  2. Decide read vs write
  3. Branch into either `doRead(rootSchemaName)` or `doWrite()`
  4. Each path measures latency, records on evaluator

`src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.target.Target
import java.time.Duration
import java.time.Instant
import java.util.Random

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val data: DataConfig,
    private val generator: BusinessEventGenerator,
    private val target: Target,
    private val untilStopCap: Duration = Duration.ofMinutes(1),
    private val decisionRandom: Random = Random(),
) {
    private val keyRegistry = KeyRegistry()
    private val schemasByName: Map<String, SchemaSpec> = data.schemas.associateBy { it.name }
    private val keyColumnByName: Map<String, String> = data.schemas.associate { schema ->
        schema.name to (schema.columns.firstOrNull { it.key }?.name
            ?: throw MisconfigurationException(
                "schema '${schema.name}' has no column marked 'key: true'. " +
                "ScenarioRunner requires the KeyColumnValidator to have passed before construction."
            ))
    }

    fun run(): ScenarioResult {
        val rateLimiter = buildRateLimiter()
        val evaluator = StopConditionEvaluator(scenario.stopConditions)
        val started = Instant.now()
        var success = 0L
        var error = 0L
        var stopReason = ""

        when (val d = scenario.duration) {
            is CountDurationSpec -> {
                while (success + error < d.value) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val td = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < td) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> {
                while (Duration.between(started, Instant.now()) < untilStopCap) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "until_stop_condition cap reached"
            }
        }

        val wall = Duration.between(started, Instant.now())
        val achievedRate = if (wall.toNanos() > 0)
            (success + error).toDouble() / (wall.toNanos() / 1_000_000_000.0) else 0.0
        return ScenarioResult(
            scenarioName = scenario.name,
            achievedRate = achievedRate,
            errorCount = error,
            successCount = success,
            stopReason = stopReason,
            wallTime = wall,
        )
    }

    private fun tick(rateLimiter: RateLimiter, evaluator: StopConditionEvaluator): Boolean {
        rateLimiter.acquire()
        val rootSchemaName = scenario.rootSchemas.first()  // multi-root weighting deferred
        val rootKeyColumn = keyColumnByName[rootSchemaName]!!
        val isRead = scenario.readRatio > 0.0 &&
            target.supportsReads &&
            decisionRandom.nextDouble() < scenario.readRatio &&
            keyRegistry.size(rootSchemaName) > 0

        val t0 = System.nanoTime()
        val success = if (isRead) {
            val key = keyRegistry.sample(rootSchemaName, decisionRandom)!!
            target.read(rootSchemaName, key).success
        } else {
            val event = generator.next()
            val rootSchema = schemasByName[rootSchemaName]!!
            val finalEvent = maybeApplyUpdate(event, rootSchema, rootKeyColumn)
            // Register the (possibly substituted) parent key for future updates/reads.
            val parentKey = finalEvent.parentRow[rootKeyColumn]!!
            keyRegistry.register(rootSchemaName, parentKey)
            // Register children's keys too.
            finalEvent.childrenBySchema.forEach { (childSchema, rows) ->
                val childKeyColumn = keyColumnByName[childSchema] ?: return@forEach
                rows.forEach { row -> row[childKeyColumn]?.let { keyRegistry.register(childSchema, it) } }
            }
            target.write(finalEvent).success
        }
        val latencyNanos = System.nanoTime() - t0
        evaluator.recordOutcome(success = success, latencyNanos = latencyNanos)
        return success
    }

    private fun maybeApplyUpdate(event: BusinessEvent, rootSchema: SchemaSpec, keyColumn: String): BusinessEvent {
        if (rootSchema.updateRatio > 0.0 &&
            keyRegistry.size(rootSchema.name) > 0 &&
            decisionRandom.nextDouble() < rootSchema.updateRatio
        ) {
            val existingKey = keyRegistry.sample(rootSchema.name, decisionRandom)!!
            val newParent = LinkedHashMap(event.parentRow)
            newParent[keyColumn] = existingKey
            return event.copy(parentRow = newParent)
        }
        return event
    }

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> RampedRateLimiter(
            fromOpsPerSecond = r.from, toOpsPerSecond = r.to,
            rampDuration = Duration.parse(r.over),
        )
        is SteppedRateSpec -> SteppedRateLimiter(
            steps = r.steps.map { StepConfig(rate = it.rate, hold = Duration.parse(it.hold)) },
        )
    }
}
```

- [ ] **Step 2: Fix existing ScenarioRunnerTest + ScenarioRunnerExtendedTest**

Both test files construct `ScenarioRunner(scenario, generator, target, untilStopCap?)`. The new constructor requires `data` as the second parameter. Update every callsite to pass the right `DataConfig`. The test helper `runner(...)` already constructs a `simpleData()` — pass it through.

- [ ] **Step 3: Write new test class for KV semantics**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerKvSemanticsTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.test.Test

class ScenarioRunnerKvSemanticsTest {

    private fun data(updateRatio: Double = 0.0) = DataConfig(2, listOf(
        SchemaSpec("customer", updateRatio, listOf(
            ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
        ))
    ))

    private fun runner(dir: Path, scenario: ScenarioSpec, target: InMemoryTarget, data: DataConfig,
                       decisionSeed: Long = 1L): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario, data, gen, target, decisionRandom = Random(decisionSeed))
    }

    @Test
    fun `read_ratio of zero produces only writes`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "writes-only", target = "t", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1000.0), duration = CountDurationSpec(50),
            transactionScope = TransactionScope.NONE, readRatio = 0.0,
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes).hasSize(50)
        assertThat(target.reads).isEmpty()
    }

    @Test
    fun `read_ratio of half produces a mix once registry warms up`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "mix", target = "t", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(200),
            transactionScope = TransactionScope.NONE, readRatio = 0.5,
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes.size + target.reads.size).isEqualTo(200)
        assertThat(target.writes).isNotEmpty()
        assertThat(target.reads).isNotEmpty()
    }

    @Test
    fun `update_ratio reuses previously registered keys`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        // High update_ratio + count duration so we can observe key reuse.
        val scenario = ScenarioSpec(
            name = "update-heavy", target = "t", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(100),
            transactionScope = TransactionScope.NONE, readRatio = 0.0,
        )
        runner(dir, scenario, target, data(updateRatio = 0.8)).run()
        // Without update_ratio, the sequence value source would emit 100 distinct keys (1..100).
        // With update_ratio=0.8 and ~80% of writes substituted with existing keys, distinct count is far less.
        val distinct = target.writes.map { it.parentRow["id"] }.toSet()
        assertThat(distinct.size).isLessThan(60)
    }
}
```

- [ ] **Step 4: Run — full suite green**

`./gradlew test` — count rises by 3 (new KV semantics tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datagen): wire KeyRegistry, update_ratio, and read_ratio into ScenarioRunner"
```

---

### Task 9: Gg8KvTarget skeleton (connection lifecycle, capability flags)

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg8KvTarget.kt`

A class that lazily opens an `IgniteClient` on first use and closes it on `close()`. Constructor takes `clusterName` and capability flags. No write/read implementations yet — those come in Tasks 10 and 11. This task just lands the skeleton + connection lifecycle.

- [ ] **Step 1: Implement skeleton**

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.client.gg8.DemoAddressFinder
import org.apache.ignite.Ignition
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration

class Gg8KvTarget(
    private val clusterName: String,
) : Target, AutoCloseable {

    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = true

    @Volatile private var client: IgniteClient? = null

    private fun ensureClient(): IgniteClient {
        val existing = client
        if (existing != null) return existing
        synchronized(this) {
            val again = client
            if (again != null) return again
            val cfg = ClientConfiguration().setAddressesFinder(DemoAddressFinder(clusterName))
            val opened = try {
                Ignition.startClient(cfg)
            } catch (e: Exception) {
                throw MisconfigurationException(
                    "Gg8KvTarget could not connect to GG8 cluster '$clusterName': ${e.message}. " +
                    "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
                    "and the cluster name matches the clusters[].name entry.",
                    cause = e,
                )
            }
            client = opened
            return opened
        }
    }

    override fun write(event: BusinessEvent): WriteOutcome {
        TODO("Plan 6 Task 10")
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        TODO("Plan 6 Task 11")
    }

    override fun close() {
        client?.close()
        client = null
    }
}
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin` — `BUILD SUCCESSFUL`.

NOTE: ignite-core 8.9.18 jars are now on the runtime classpath. If `Ignition` or `IgniteClient` or `ClientConfiguration` cannot be resolved, the gridgain external maven repo or the gg8-client-finder dep are not picked up. Verify build.gradle.kts before moving on.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/target/Gg8KvTarget.kt
git commit -m "feat(datagen): add Gg8KvTarget skeleton with connection lifecycle"
```

---

### Task 10: Gg8KvTarget.write — KV puts with optional transaction

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg8KvTarget.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/target/Gg8KvTargetWriteTest.kt`

The test class is gated by env var so it only runs when a real GG8 cluster is configured.

- [ ] **Step 1: Implement write**

Replace `Gg8KvTarget.write` body:

```kotlin
override fun write(event: BusinessEvent): WriteOutcome {
    return try {
        val ignite = ensureClient()
        val tx = ignite.transactions().txStart()
        try {
            putRow(ignite, schemaName = parentSchemaName(event), row = event.parentRow)
            event.childrenBySchema.forEach { (childSchema, rows) ->
                rows.forEach { row -> putRow(ignite, schemaName = childSchema, row = row) }
            }
            tx.commit()
            WriteOutcome(success = true)
        } catch (e: Exception) {
            tx.rollback()
            WriteOutcome(success = false, error = e)
        }
    } catch (e: Exception) {
        WriteOutcome(success = false, error = e)
    }
}

private fun parentSchemaName(event: BusinessEvent): String =
    // BusinessEvent doesn't currently carry the parent schema name explicitly.
    // For Plan 6, the runner is expected to record the parent schema via a future contract;
    // the simplest workaround is for the runner to use a wrapping decorator. For now, this
    // method is unused — write() is called by the runner which knows the schema name and
    // handles cache routing externally. This indicates the contract needs a small extension.
    error("not yet wired: see Plan 6 Task 10 followup")

private fun putRow(ignite: IgniteClient, schemaName: String, row: Map<String, Any?>) {
    val keyColumn = TODO("inject keyColumnByName from runner; see Task 10 design note below")
    val key = row[keyColumn] ?: throw IllegalStateException(
        "row of schema '$schemaName' has null value in key column '$keyColumn'."
    )
    val cache = ignite.cache<Any, Map<String, Any?>>(schemaName).withKeepBinary<Any, Map<String, Any?>>()
    cache.put(key, row)
}
```

**Design note: this task surfaces a contract gap.** `BusinessEvent` doesn't carry the parent schema name, and `Gg8KvTarget.write()` doesn't know the key column for each schema. Two ways to plug the gap:

A) **Recommended:** ScenarioRunner constructs `Gg8KvTarget` with a `Map<String, String>` of `schemaName → keyColumn` so the target can resolve key columns internally.

B) Extend `BusinessEvent` to carry `parentSchemaName: String` and each child row to be in a typed `(schemaName, rows)` pair instead of a Map. Larger change.

Plan 6 takes option A: add a `keyColumnByName: Map<String, String>` constructor parameter to `Gg8KvTarget`. The runner builds this map from `data.schemas` and passes it in.

Update the constructor and the implementation accordingly:

```kotlin
class Gg8KvTarget(
    private val clusterName: String,
    private val keyColumnByName: Map<String, String>,
) : Target, AutoCloseable {

    // ... ensureClient(), close() unchanged ...

    override fun write(event: BusinessEvent): WriteOutcome {
        return try {
            val ignite = ensureClient()
            val tx = ignite.transactions().txStart()
            try {
                // For Plan 6, the parent schema name is the first root_schema of the scenario;
                // ScenarioRunner is responsible for building events whose schema-name resolution
                // happens at the cache lookup. We assume the parent's key column is resolvable
                // via keyColumnByName; the parent's "schema name" is communicated by the runner
                // calling write(event) with an event whose first key column resolves under the
                // root schema. Plan 7+ may make this explicit by adding parentSchemaName to BusinessEvent.
                //
                // Pragmatic solution: try every schema's keyColumn until one matches.
                val parentKeyColumn = keyColumnByName.values.firstOrNull { col -> event.parentRow.containsKey(col) }
                    ?: throw IllegalStateException("could not resolve parent schema's key column from event")
                val parentSchemaName = keyColumnByName.entries.first { it.value == parentKeyColumn }.key
                putRow(ignite, parentSchemaName, parentKeyColumn, event.parentRow)
                event.childrenBySchema.forEach { (childSchema, rows) ->
                    val childKeyColumn = keyColumnByName[childSchema]
                        ?: throw IllegalStateException("no key column registered for schema '$childSchema'")
                    rows.forEach { row -> putRow(ignite, childSchema, childKeyColumn, row) }
                }
                tx.commit()
                WriteOutcome(success = true)
            } catch (e: Exception) {
                tx.rollback()
                WriteOutcome(success = false, error = e)
            }
        } catch (e: Exception) {
            WriteOutcome(success = false, error = e)
        }
    }

    private fun putRow(ignite: IgniteClient, schemaName: String, keyColumn: String, row: Map<String, Any?>) {
        val key = row[keyColumn] ?: throw IllegalStateException(
            "row of schema '$schemaName' has null value in key column '$keyColumn'."
        )
        val cache = ignite.getOrCreateCache<Any, Map<String, Any?>>(schemaName)
        cache.put(key, row)
    }
}
```

NOTE: this design is heuristic for the parent schema; future plans should add `parentSchemaName` to `BusinessEvent` for clarity. Track as a follow-up.

- [ ] **Step 2: Update ScenarioRunner construction site for Gg8 case**

ScenarioRunner doesn't construct the target directly — its caller (a future driver in Plan 8) does. For Plan 6 the runner just receives a `Target` instance. Wherever the integration tests construct `Gg8KvTarget`, they pass `keyColumnByName`.

- [ ] **Step 3: Write integration test**

`src/test/kotlin/com/gridgain/demo/datagen/target/Gg8KvTargetWriteTest.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8KvTargetWriteTest {

    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test"

    @Test
    fun `write puts a parent row to the named cache`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val parent = LinkedHashMap<String, Any?>().apply {
                put("id", 1001L); put("name", "Alice")
            }
            val event = BusinessEvent(parentRow = parent, childrenBySchema = emptyMap())
            val outcome = target.write(event)
            assertThat(outcome.success).isTrue()
        }
    }
}
```

- [ ] **Step 4: Run targeted (only if env vars set)**

`DATAGEN_GG8_CLUSTER_NAME=<your-cluster> ./gradlew test --tests 'com.gridgain.demo.datagen.target.Gg8KvTargetWriteTest'`

If env var is unset, JUnit should skip the class. If set, the test should pass against a real cluster.

- [ ] **Step 5: Run full suite (env vars unset → integration tests skip)**

`./gradlew test` — green, integration test class skipped.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(datagen): Gg8KvTarget.write with transaction wrapping"
```

---

### Task 11: Gg8KvTarget.read

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg8KvTarget.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/target/Gg8KvTargetReadTest.kt`

- [ ] **Step 1: Implement read**

Replace `Gg8KvTarget.read` body:

```kotlin
override fun read(cacheName: String, key: Any): ReadOutcome {
    return try {
        val ignite = ensureClient()
        val cache = ignite.getOrCreateCache<Any, Map<String, Any?>>(cacheName)
        val value = cache.get(key)
        ReadOutcome(success = true, value = value)
    } catch (e: Exception) {
        ReadOutcome(success = false, error = e)
    }
}
```

- [ ] **Step 2: Integration test**

`src/test/kotlin/com/gridgain/demo/datagen/target/Gg8KvTargetReadTest.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8KvTargetReadTest {

    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test"

    @Test
    fun `read returns a value previously written`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val key = 2002L
            val parent = LinkedHashMap<String, Any?>().apply { put("id", key); put("note", "hello") }
            target.write(BusinessEvent(parentRow = parent, childrenBySchema = emptyMap()))
            val outcome = target.read(cacheName, key)
            assertThat(outcome.success).isTrue()
            assertThat(outcome.value).isNotNull
        }
    }

    @Test
    fun `read returns success-with-null for absent key`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val outcome = target.read(cacheName, key = -999_999L)
            assertThat(outcome.success).isTrue()
            assertThat(outcome.value).isNull()
        }
    }
}
```

- [ ] **Step 3: Run targeted + full suite**

Same env-var pattern as Task 10.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(datagen): Gg8KvTarget.read"
```

---

### Task 12: End-to-end smoke + final review

**Files:** verification only.

- [ ] **Step 1: End-to-end against the cluster (manual smoke)**

Author a `data.yaml`:
```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.20
    columns:
      - name: id
        null_rate: 0.0
        key: true
        affinity: true
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: name
        null_rate: 0.02
        value_source: { kind: datafaker, expression: "#{name.fullName}" }
```

…and an `ops.yaml`:
```yaml
schema_version: 2
targets:
  - name: gg8-test
    kind: gg8-kv
    cluster_name: <your-cluster-name>
scenarios:
  - name: smoke
    target: gg8-test
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 50 }
    duration: { kind: count, value: 100 }
    transaction_scope: business_event
    read_ratio: 0.10
```

Plug them into `ConfigurationParser`; build a `BusinessEventGenerator` and `Gg8KvTarget`; run `ScenarioRunner.run()`. Inspect:
- `result.successCount` should be ~100
- `result.errorCount` should be small (network burps OK)
- The cluster's `customer` cache should now contain ~80–95 entries (some keys were "updates" overwriting earlier keys).
- A small fraction of operations were reads (read_ratio = 0.10 with the registry warming up early).

- [ ] **Step 2: `./gradlew clean test`** — full suite green WITHOUT env vars (integration tests skip).

- [ ] **Step 3: Final cross-cutting review** via `superpowers:code-reviewer` once execution is complete.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §3 GG8 KV target | Tasks 1, 9, 10, 11 |
| §3 transactions on KV target | Task 10 |
| §3 Target capability flags | Task 6 |
| §3 read execution | Tasks 6, 8, 11 |
| §1 update_ratio execution | Tasks 7, 8 |
| §1 affinity column annotation (already in v2 from Plan 5) | (Plan 5 Task 1) |
| §1 key column annotation | Tasks 2, 3 |
| §6 cross-element: scenario-target compat, key column required | Tasks 3, 5 |
| Project rule: rich error messages | Tasks 5, 9 |
| Project rule: reuse `gridgain-demo-client-utils` | Tasks 1, 9 |

**Out of scope of this plan (deferred):**
- GG9 KV target — Plan 7.
- Provisioning emit + apply (cache config XML, GG9 SQL DDL) — Plan 8.
- State persistence (KeyRegistry across runs) — Plan 9.
- OTel — Plan 10.
- CLI + plugin invocation — Plan 11.
- `BusinessEvent.parentSchemaName` extension to remove the heuristic schema lookup in `Gg8KvTarget.write` — followup task.

---

## Critical files (forward references for Plan 7+)

- `target/Gg8KvTarget.kt` — Plan 7 mirrors this for `Gg9KvTarget` (different transaction API, different cache → table model).
- `config/TargetSpec.kt` — Plan 7 adds `Gg9KvTargetSpec`.
- `scenario/ScenarioRunner.kt` — Plan 8+ wraps it with state persistence so KeyRegistry survives across runs.
- `scenario/KeyRegistry.kt` — Plan 9 makes it persistent (read/write to `state.yaml`).
