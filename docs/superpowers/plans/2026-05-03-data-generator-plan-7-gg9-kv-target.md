# Data Generator — Plan 7: GG9 KV Target

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror Plan 6 for GridGain 9 — connect the data generator to a real GG9 cluster via the existing `gg9-client-finder` plumbing. Add the `Gg9KvTargetSpec` branch to `TargetSpec`, the `Gg9KvTarget` runtime, the matching capability branch in `ScenarioTargetValidator.capabilitiesFor`, the matching CLI dispatch branch in `cli/Main.kt`, the `target_gg9_kv` JSONSchema definition, and env-gated integration tests for `write` and `read`. Plan 7 is **shorter than Plan 6**: all the cross-cutting infrastructure (`KeyRegistry`, `ScenarioRunner` KV semantics, `key: true` annotation, `TargetSpec` sealed hierarchy, `ScenarioTargetValidator`, `Target` interface, `ReadOutcome`, `BusinessEvent`) already exists from Plan 6.

**Architecture:** GG9 ships a single `IgniteClient` (no thin/thick split). Endpoint resolution comes from `gg9-client-finder`'s `DemoAddressFinder`, which returns a `String[]` and is wired via `IgniteClient.builder().addressFinder(...)`. KV access is through `client.tables().table(<tableName>).keyValueView(...)`; the closest GG8 analog to a "cache named after the schema" is a GG9 **table** named after the schema. `Gg9KvTarget(clusterName, keyColumnByName, transactionScope)` lazily builds an `IgniteClient` on first `write`/`read` call, and on `write(event)` puts the parent's key/value pair to the table named after the parent schema, then iterates `childrenBySchema` doing the same for each child row. When `transaction_scope: business_event`, the entire subtree is wrapped in `client.transactions().runInTransaction { tx -> ... }`. `read(tableName, keyValue)` does `client.tables().table(tableName).keyValueView(...).get(null, key)`. Integration tests are annotated `@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")` so they no-op without a cluster.

**Tech Stack additions:**
- `com.gridgain.demo:gg9-client-finder:0.0.5-SNAPSHOT` (must be `publishToMavenLocal`'d first from `gridgain-demo-client-utils`)
- `org.gridgain:ignite-client:9.1.3`
- The GridGain external maven repo is already declared (Plan 6).

**Pre-execution prerequisites (operator must satisfy before Task 1):**
1. `cd ../gridgain-demo-client-utils && ./gradlew publishToMavenLocal` (re-publishes `gg9-client-finder` alongside `gg8-client-finder`).
2. A reachable GG9 cluster (single-node Docker locally OR plugin-deployed in GCP).
3. A `client-endpoints.yaml` with a `clusters[]` entry naming this cluster (the same file already used for GG8). Surfaced via `GG_DEMO_CLIENT_ENDPOINTS` env var or default path resolution.
4. A pre-created GG9 table whose name and key-column match the env vars below. The data generator does **not** provision tables in this plan — Plan 9 (Provisioning Emit + Apply) closes that gap.
5. Three env vars exported in the shell that runs integration tests:
   - `DATAGEN_GG9_CLUSTER_NAME=<the cluster name>`
   - `DATAGEN_GG9_TEST_TABLE=<a writable table in that cluster, e.g. `data_gen_test`>`
   - `GG_DEMO_CLIENT_ENDPOINTS=<absolute path to client-endpoints.yaml>` (consumed by `DemoAddressFinder` via `AddressResolution`).

---

## Spec extensions (additive on top of Plan 6's v2)

```yaml
# ops.yaml — additive: a second target kind
schema_version: 2
targets:
  - name: gg9-trip-cluster
    kind: gg9-kv
    cluster_name: trip-cluster-9
scenarios:
  - name: customer-load
    target: gg9-trip-cluster
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 50 }
    duration: { kind: time, value: PT15S }
    transaction_scope: business_event
    read_ratio: 0.10
```

`cluster_name` references an entry in `client-endpoints.yaml` per the existing `gg9-client-finder` contract.

**No `data.yaml` changes.** All data-shape concerns (key column annotation, cohort buckets, parent-fk-ref, null_rate) are GG-version-agnostic and already in place from Plan 6.

**No new `schema_version` bump.** The `target_gg9_kv` definition is additive under the v2 ops `target` `oneOf`; v1 → v2 migration is unaffected.

---

## File Structure (deltas only)

```
gridgain-demo-data-generator/
├── build.gradle.kts                      # add gg9-client-finder + ignite-client deps; manage GG8/GG9 classpath conflict
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── TargetSpec.kt                 # add Gg9KvTargetSpec (data class + @JsonSubTypes entry)
│   │   └── CrossElementValidator.kt      # add `is Gg9KvTargetSpec ->` branch in capabilitiesFor
│   ├── target/
│   │   └── Gg9KvTarget.kt                # NEW: mirror of Gg8KvTarget against the GG9 client API
│   └── cli/
│       └── Main.kt                       # add `is Gg9KvTargetSpec ->` dispatch branch
├── src/main/resources/schema/
│   └── ops/v2.schema.json                # add target_gg9_kv definition under target oneOf
└── src/test/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   ├── Gg9KvTargetSpecDeserializationTest.kt  # NEW
    │   └── ScenarioTargetValidatorTest.kt         # extend with gg9 capability cases
    └── target/
        ├── Gg9KvTargetWriteTest.kt        # NEW (env-gated)
        └── Gg9KvTargetReadTest.kt         # NEW (env-gated)
```

---

## GG8/GG9 classpath conflict — strategy decision

`org.gridgain:ignite-core:8.9.18` (Apache Ignite 2.x lineage) and `org.gridgain:ignite-client:9.1.3` (Apache Ignite 3.x lineage) carry overlapping but incompatible packages (`org.apache.ignite.*`). Putting both on the same classpath is a known source of `NoClassDefFoundError` and `IncompatibleClassChangeError` at runtime.

The data generator already declares GG8 `ignite-core` as `implementation` (Plan 6 Task 1). Plan 7's options:

| Option | Description | Verdict |
|---|---|---|
| A — replace GG8 with GG9 | Drop `ignite-core:8.9.18` and add `ignite-client:9.1.3`. | **Rejected.** Breaks `Gg8KvTarget` and all GG8 tests. |
| B — separate test source set | Add a `gg9Test` source set with its own classpath that includes GG9 but excludes GG8. | Heavy — builds two test JVMs. |
| C — forked test JVM with classpath swap | Per-test-class JVM forking and conditional classpath. | Fragile, hard to maintain. |
| D — both deps `implementation`, accept risk | Hope the package overlap doesn't bite. | **Rejected.** Documented incompatibility. |
| E — keep GG8 `implementation`, add GG9 as `compileOnly` + integration-test `testImplementation` | Mirrors how `gg8-client-finder` and `gg9-client-finder` already manage their `ignite-*` deps (compileOnly in main, testImplementation in test). The data generator's main source compiles `Gg9KvTarget` against the GG9 API, but the runtime classpath at scenario-run time only carries one of GG8 or GG9 depending on how the consumer (CLI / plugin) builds the classpath. | **Selected.** |

**Why E works:** `cli/Main.kt` resolves the target type at runtime; only one branch (GG8 OR GG9) executes per process. The plugin's `DataGenerateTask` (Plan 8) constructs the forked JVM's classpath — it can choose which client jar to add based on the resolved `TargetSpec`. For unit tests inside the data generator itself, GG9 client is on `testImplementation` so deserialization tests compile, and the env-gated GG9 integration tests get the runtime jars.

**Operator-facing consequence:** until Plan 8 is updated to dispatch the right client jar based on target kind, running a GG9 scenario via the CLI requires the operator to put `ignite-client:9.1.3` on the runtime classpath manually. Document this in the README and in F8 below.

A new follow-up F8 captures the Plan 8 update needed:

> **F8 — Plan 8 `DataGenerateTask` classpath split for GG8 vs GG9.**
> Today `DataGenerateTask` (Plan 8) bundles `ignite-core:8.9.18` into the forked JVM unconditionally. Once Plan 7 lands, the task must inspect the resolved target's `kind` and add `ignite-core:8.9.18` (gg8-kv) OR `ignite-client:9.1.3` (gg9-kv) — never both. Track this against Plan 8 follow-up work.

---

### Task 1: Build wiring — gg9-client-finder + ignite-client (compileOnly + testImplementation)

**Files:**
- Modify: `build.gradle.kts`

Add `gg9-client-finder` and `ignite-client` per Option E above. Keep GG8 `implementation` unchanged.

- [ ] **Step 1: Edit dependencies block in `build.gradle.kts`**

Replace the existing `dependencies { ... }` block:

```kotlin
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

    // Plan 7 — GG9 KV target
    // gg9-client-finder declares ignite-client as compileOnly; we mirror that here so
    // GG8's ignite-core (8.9.18) and GG9's ignite-client (9.1.3) never end up on the
    // SAME runtime classpath (their org.apache.ignite.* packages overlap incompatibly).
    // The CLI / plugin chooses which client jar to add at scenario-run time based on
    // the resolved target kind. See Plan 7 Option E and follow-up F8.
    implementation("com.gridgain.demo:gg9-client-finder:0.0.5-SNAPSHOT")
    compileOnly("org.gridgain:ignite-client:9.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))

    // Plan 7 — env-gated GG9 integration tests need the GG9 client at test runtime.
    testImplementation("org.gridgain:ignite-client:9.1.3")
}
```

- [ ] **Step 2: Verify dependency resolution**

```bash
./gradlew --no-daemon build -x test
```

Expected: `BUILD SUCCESSFUL`. If `com.gridgain.demo:gg9-client-finder:0.0.5-SNAPSHOT` is unresolved, run `cd ../gridgain-demo-client-utils && ./gradlew publishToMavenLocal` and retry.

- [ ] **Step 3: Verify the suite still passes**

```bash
./gradlew test
```

All 142 prior tests still pass (139 unit + 3 env-gated integration tests skipped without env vars).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(datagen): add gg9-client-finder + ignite-client deps for Plan 7

Mirrors gg9-client-finder's compileOnly/testImplementation pattern so
ignite-core 8.9.18 and ignite-client 9.1.3 never share a runtime
classpath. CLI / plugin dispatches the correct client jar at scenario-
run time based on the resolved target kind.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add `Gg9KvTargetSpec` to the `TargetSpec` sealed hierarchy

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/TargetSpec.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt`

The hierarchy is the canonical extension point. Add a new `data class` and a new `@JsonSubTypes.Type` entry; the polymorphic Jackson dispatch handles deserialization automatically.

- [ ] **Step 1: Update `TargetSpec.kt`**

Replace the file body:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Gg8KvTargetSpec::class, name = "gg8-kv"),
    JsonSubTypes.Type(value = Gg9KvTargetSpec::class, name = "gg9-kv"),
)
sealed class TargetSpec {
    abstract val name: String
}

data class Gg8KvTargetSpec(
    override val name: String,
    @JsonProperty("cluster_name") val clusterName: String,
) : TargetSpec()

data class Gg9KvTargetSpec(
    override val name: String,
    @JsonProperty("cluster_name") val clusterName: String,
) : TargetSpec()
```

- [ ] **Step 2: Write the deserialization test**

`src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg9KvTargetSpecDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `gg9-kv target deserializes into Gg9KvTargetSpec`() {
        val yaml = """
            kind: gg9-kv
            name: gg9-trip
            cluster_name: trip-cluster-9
        """.trimIndent()
        val target: TargetSpec = mapper.readValue(yaml, TargetSpec::class.java)
        assertThat(target).isInstanceOf(Gg9KvTargetSpec::class.java)
        target as Gg9KvTargetSpec
        assertThat(target.name).isEqualTo("gg9-trip")
        assertThat(target.clusterName).isEqualTo("trip-cluster-9")
    }

    @Test
    fun `gg8-kv target still deserializes into Gg8KvTargetSpec (regression guard)`() {
        val yaml = """
            kind: gg8-kv
            name: gg8-trip
            cluster_name: trip-cluster-8
        """.trimIndent()
        val target: TargetSpec = mapper.readValue(yaml, TargetSpec::class.java)
        assertThat(target).isInstanceOf(Gg8KvTargetSpec::class.java)
    }
}
```

- [ ] **Step 3: Run targeted + full suite**

```bash
./gradlew test --tests 'com.gridgain.demo.datagen.config.Gg9KvTargetSpecDeserializationTest'
./gradlew test
```

Expected: 2 new tests PASS, full suite green (144 tests).

NOTE: The `ScenarioTargetValidator.capabilitiesFor` `when` expression is now non-exhaustive (it covers only `Gg8KvTargetSpec`). Kotlin's `when` over a sealed class is exhaustive only if every subtype is enumerated; depending on the compiler version, this may produce a *warning* or a *hard error*. Task 3 closes this immediately. If the compile breaks at this step, skip ahead to Task 3 Step 1, then return.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/TargetSpec.kt src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): add Gg9KvTargetSpec to TargetSpec sealed hierarchy

Plan 7 Task 2. Polymorphic Jackson dispatch on `kind: gg9-kv` produces
Gg9KvTargetSpec; deserialization parity with Gg8KvTargetSpec verified.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: ScenarioTargetValidator — capability branch for `Gg9KvTargetSpec`

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioTargetValidatorTest.kt`

GG9 KV supports both reads and transactions. Add the `is Gg9KvTargetSpec ->` branch in the private `capabilitiesFor` and add the matching test cases.

- [ ] **Step 1: Add the capability branch**

In `CrossElementValidator.kt`, replace the body of `ScenarioTargetValidator.capabilitiesFor`:

```kotlin
    private fun capabilitiesFor(target: TargetSpec): Pair<Boolean, Boolean> = when (target) {
        is Gg8KvTargetSpec -> true to true
        is Gg9KvTargetSpec -> true to true
    }
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`. The sealed-class `when` is now exhaustive again.

- [ ] **Step 3: Extend the existing test class with gg9 cases**

Append to `src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioTargetValidatorTest.kt` (new test methods inside the existing class):

```kotlin
    private fun gg9(name: String) = Gg9KvTargetSpec(name = name, clusterName = "trip-9")

    @Test
    fun `gg9 target — accepts a scenario referencing it`() {
        val ops = OpsConfig(
            schemaVersion = 2,
            targets = listOf(gg9("gg9-trip")),
            scenarios = listOf(scenario("s", "gg9-trip")),
        )
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `gg9 target supports reads — read_ratio gt 0 accepted`() {
        val ops = OpsConfig(
            schemaVersion = 2,
            targets = listOf(gg9("gg9-trip")),
            scenarios = listOf(scenario("s", "gg9-trip", readRatio = 0.5)),
        )
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `gg9 target supports transactions — business_event accepted`() {
        val ops = OpsConfig(
            schemaVersion = 2,
            targets = listOf(gg9("gg9-trip")),
            scenarios = listOf(scenario("s", "gg9-trip", tx = TransactionScope.BUSINESS_EVENT)),
        )
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }
```

- [ ] **Step 4: Run targeted + full suite**

```bash
./gradlew test --tests 'com.gridgain.demo.datagen.config.ScenarioTargetValidatorTest'
./gradlew test
```

Expected: 8 PASS in the validator class (5 existing + 3 new); full suite green (147 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(datagen): ScenarioTargetValidator capability branch for gg9-kv

Plan 7 Task 3. Closes the sealed-class exhaustiveness gap introduced
by Task 2. GG9 KV declares supportsReads = supportsTransactions = true
to mirror GG8 KV.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Update v2 ops JSONSchema — `target_gg9_kv` definition

**Files:**
- Modify: `src/main/resources/schema/ops/v2.schema.json`

The `target` `oneOf` must accept both `gg8-kv` and `gg9-kv` shapes. The two shapes are structurally identical (`{kind, name, cluster_name}`); only the `kind` const differs.

- [ ] **Step 1: Edit `v2.schema.json`**

In `$defs`, replace the `target` definition and append a `target_gg9_kv` definition next to `target_gg8_kv`:

```json
    "target": {
      "oneOf": [
        { "$ref": "#/$defs/target_gg8_kv" },
        { "$ref": "#/$defs/target_gg9_kv" }
      ]
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
    },
    "target_gg9_kv": {
      "type": "object",
      "required": ["kind", "name", "cluster_name"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "gg9-kv" },
        "name": { "type": "string", "minLength": 1 },
        "cluster_name": { "type": "string", "minLength": 1 }
      }
    }
```

- [ ] **Step 2: Add a JSONSchema validation test**

Mirror the existing JSONSchema test pattern. Search for the existing ops-v2 schema test (likely `JsonSchemaValidatorTest.kt` or a sibling). Append a test that an `ops.yaml` with `kind: gg9-kv` validates clean:

```kotlin
    @Test
    fun `ops v2 schema accepts a gg9-kv target`() {
        val yaml = """
            schema_version: 2
            targets:
              - { kind: gg9-kv, name: gg9-trip, cluster_name: trip-cluster-9 }
            scenarios:
              - name: s
                target: gg9-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 100 }
                transaction_scope: business_event
                read_ratio: 0.0
        """.trimIndent()
        // No exception means the schema accepted the document.
        JsonSchemaValidator.validateOps(yaml, fileName = "ops.yaml")
    }
```

If no such test class exists yet, place this test in `src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt` as an additional `@Test` method (the schema-validation call is one line and doesn't need its own class).

- [ ] **Step 3: Run targeted + full suite**

```bash
./gradlew test --tests 'com.gridgain.demo.datagen.config.*'
./gradlew test
```

Expected: full suite green (148 tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/schema/ops/v2.schema.json src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): ops v2 schema accepts target_gg9_kv

Plan 7 Task 4. Adds the gg9-kv shape under the target oneOf; validates
parity with target_gg8_kv via JSONSchema test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `Gg9KvTarget` — connection lifecycle skeleton

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt`

Lazily build an `IgniteClient` via `IgniteClient.builder().addressFinder(DemoAddressFinder(clusterName)).build()`. Close it in `close()`. Capability flags hard-coded to `true` (matches `ScenarioTargetValidator.capabilitiesFor`).

The GG9 client API differs from GG8 in two ways relevant here:
1. **Builder vs configuration object.** GG9 uses `IgniteClient.builder()` (fluent), not `ClientConfiguration` + `Ignition.startClient`.
2. **Finder interface.** `gg9-client-finder.DemoAddressFinder` implements `org.apache.ignite.client.IgniteClientAddressFinder` (note: top-level `client` package in GG9, distinct from GG8's `org.apache.ignite.configuration` location).

`write` and `read` start as `TODO` and are filled in Tasks 6 and 7.

- [ ] **Step 1: Create the file**

`src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient

/**
 * GG9 KV target. Lazily builds an [IgniteClient] on first `write` or `read` call.
 * Closes the client on `close()`.
 *
 * @param clusterName must match a `clusters[].name` entry in the resolved client-endpoints.yaml
 * @param keyColumnByName maps each schema name to the name of its key column. The runner
 *     constructs this map from the parsed `DataConfig`.
 * @param transactionScope controls whether `write()` wraps the parent + child puts in a single
 *     GG9 transaction. Defaults to `NONE`. Set to `BUSINESS_EVENT` to opt in.
 *
 * GG9 note: in GridGain 9 a "cache" is a "table". The target writes a row into the table named
 * after the schema. Tables must already exist in the cluster — this target does not provision
 * them. Plan 9 (Provisioning Emit + Apply) closes that gap.
 */
class Gg9KvTarget(
    private val clusterName: String,
    private val keyColumnByName: Map<String, String>,
    private val transactionScope: TransactionScope = TransactionScope.NONE,
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
            val opened = try {
                IgniteClient.builder()
                    .addressFinder(DemoAddressFinder(clusterName))
                    .build()
            } catch (e: Exception) {
                throw MisconfigurationException(
                    "Gg9KvTarget could not connect to GG9 cluster '$clusterName': ${e.message}. " +
                    "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path " +
                    "(set GG_DEMO_CLIENT_ENDPOINTS), and the cluster name matches the clusters[].name entry.",
                    cause = e,
                )
            }
            client = opened
            return opened
        }
    }

    override fun write(event: BusinessEvent): WriteOutcome {
        TODO("Plan 7 Task 6")
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        TODO("Plan 7 Task 7")
    }

    override fun close() {
        client?.close()
        client = null
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`. If `IgniteClient` cannot be resolved, verify `compileOnly("org.gridgain:ignite-client:9.1.3")` in `build.gradle.kts`.

- [ ] **Step 3: Run full suite (still no GG9 calls hooked into the runtime)**

```bash
./gradlew test
```

Expected: green, 148 tests. The new file compiles but is unreferenced.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt
git commit -m "$(cat <<'EOF'
feat(datagen): Gg9KvTarget skeleton with lazy IgniteClient lifecycle

Plan 7 Task 5. write() and read() are TODOs; Tasks 6 and 7 fill them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `Gg9KvTarget.write` — KeyValueView put with optional transaction

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/target/Gg9KvTargetWriteTest.kt`

Mirrors `Gg8KvTarget.write` line-for-line in shape. Differences:
- **Lookup table not cache.** `client.tables().table(schemaName)` returns a `Table`. KV-flavored operations go through `table.keyValueView(...)`.
- **KeyValueView shape.** GG9's `KeyValueView<K, V>` separates key from value-record. For Plan 7's Map-shaped rows, the most expedient view is the `Tuple`-flavored one: `table.keyValueView()` returns `KeyValueView<Tuple, Tuple>`. We build a single-column key `Tuple` from the row's key column and a multi-column value `Tuple` from the rest of the row.
- **Transaction API.** `client.transactions().runInTransaction { tx -> ... }` is a closure-based API; the closure runs against an explicit `Transaction`. The KeyValueView `put(tx, key, value)` overload accepts a `Transaction` (or `null` for auto-commit).

If the `Tuple` API turns out not to be the right shape for the data generator's row maps, fall back to the table's record view with a row class — but that requires a class per schema, which Plan 7 will not maintain. **Tuples are the right primitive for runtime-typed rows**; keep them.

- [ ] **Step 1: Replace `write` and add `putAllForEvent` / `putRow` helpers**

Replace the body of `Gg9KvTarget` (preserving `ensureClient`, `close`, capability fields, and the `read` TODO):

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.table.Tuple
import org.apache.ignite.tx.Transaction

class Gg9KvTarget(
    private val clusterName: String,
    private val keyColumnByName: Map<String, String>,
    private val transactionScope: TransactionScope = TransactionScope.NONE,
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
            val opened = try {
                IgniteClient.builder()
                    .addressFinder(DemoAddressFinder(clusterName))
                    .build()
            } catch (e: Exception) {
                throw MisconfigurationException(
                    "Gg9KvTarget could not connect to GG9 cluster '$clusterName': ${e.message}. " +
                    "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path " +
                    "(set GG_DEMO_CLIENT_ENDPOINTS), and the cluster name matches the clusters[].name entry.",
                    cause = e,
                )
            }
            client = opened
            return opened
        }
    }

    override fun write(event: BusinessEvent): WriteOutcome {
        return try {
            val ignite = ensureClient()
            if (transactionScope == TransactionScope.BUSINESS_EVENT) {
                ignite.transactions().runInTransaction<Unit> { tx ->
                    putAllForEvent(ignite, tx, event)
                }
                WriteOutcome(success = true)
            } else {
                putAllForEvent(ignite, tx = null, event = event)
                WriteOutcome(success = true)
            }
        } catch (e: Exception) {
            WriteOutcome(success = false, error = e)
        }
    }

    private fun putAllForEvent(ignite: IgniteClient, tx: Transaction?, event: BusinessEvent) {
        val parentKeyColumn = keyColumnByName.values.firstOrNull { col -> event.parentRow.containsKey(col) }
            ?: throw IllegalStateException(
                "could not resolve parent schema's key column from event; " +
                "event.parentRow keys=${event.parentRow.keys}, registered key columns=${keyColumnByName.values}"
            )
        val parentSchemaName = keyColumnByName.entries.first { it.value == parentKeyColumn }.key
        putRow(ignite, tx, parentSchemaName, parentKeyColumn, event.parentRow)
        event.childrenBySchema.forEach { (childSchema, rows) ->
            val childKeyColumn = keyColumnByName[childSchema]
                ?: throw IllegalStateException("no key column registered for schema '$childSchema'")
            rows.forEach { row -> putRow(ignite, tx, childSchema, childKeyColumn, row) }
        }
    }

    private fun putRow(
        ignite: IgniteClient,
        tx: Transaction?,
        schemaName: String,
        keyColumn: String,
        row: Map<String, Any?>,
    ) {
        val keyValue = row[keyColumn] ?: throw IllegalStateException(
            "row of schema '$schemaName' has null value in key column '$keyColumn'."
        )
        val table = ignite.tables().table(schemaName) ?: throw IllegalStateException(
            "GG9 table '$schemaName' does not exist in the cluster. " +
            "Pre-create the table or run with provisioning (Plan 9, deferred)."
        )
        val keyTuple = Tuple.create().set(keyColumn, keyValue)
        val valueTuple = Tuple.create()
        for ((col, v) in row) {
            if (col == keyColumn) continue
            valueTuple.set(col, v)
        }
        table.keyValueView().put(tx, keyTuple, valueTuple)
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        TODO("Plan 7 Task 7")
    }

    override fun close() {
        client?.close()
        client = null
    }
}
```

NOTE: this design copies Plan 6's heuristic for inferring `parentSchemaName` from the key column. It inherits **follow-up F5** (BusinessEvent.parentSchemaName extension). Plan 7 does **not** fix F5 — it would benefit both targets equally and is best done as its own refactor.

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Write the env-gated integration test**

`src/test/kotlin/com/gridgain/demo/datagen/target/Gg9KvTargetWriteTest.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9KvTargetWriteTest {

    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test"

    @Test
    fun `write puts a parent row to the named table`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val parent = LinkedHashMap<String, Any?>().apply {
                put("id", 1001L); put("name", "Alice")
            }
            val event = BusinessEvent(parentRow = parent, childrenBySchema = emptyMap())
            val outcome = target.write(event)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "write failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
        }
    }
}
```

- [ ] **Step 4: Run targeted (only if env vars set)**

```bash
DATAGEN_GG9_CLUSTER_NAME=<your-cluster> \
DATAGEN_GG9_TEST_TABLE=data_gen_test \
GG_DEMO_CLIENT_ENDPOINTS=<absolute-path-to-client-endpoints.yaml> \
./gradlew test --tests 'com.gridgain.demo.datagen.target.Gg9KvTargetWriteTest'
```

If env var is unset, JUnit skips the class. If set, the test should pass against a real cluster with a pre-created `data_gen_test` table whose primary key column is `id` (BIGINT) and which has a `name` column (VARCHAR).

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```

Expected: green, 148 tests (integration test class skipped without env vars).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(datagen): Gg9KvTarget.write with optional business_event transaction

Plan 7 Task 6. KeyValueView<Tuple, Tuple> puts split each row into a
key tuple (the column marked key: true) and a value tuple (the rest).
Transaction wrapping uses runInTransaction { tx -> ... }.

Inherits follow-up F5 (BusinessEvent.parentSchemaName) — same heuristic
as Gg8KvTarget. Will be fixed once F5 is scheduled.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `Gg9KvTarget.read` — KeyValueView get

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/target/Gg9KvTargetReadTest.kt`

- [ ] **Step 1: Replace `read` body**

In `Gg9KvTarget.kt`, replace the `read` method:

```kotlin
    override fun read(cacheName: String, key: Any): ReadOutcome {
        return try {
            val ignite = ensureClient()
            val table = ignite.tables().table(cacheName) ?: throw IllegalStateException(
                "GG9 table '$cacheName' does not exist in the cluster."
            )
            val keyColumn = keyColumnByName[cacheName]
                ?: throw IllegalStateException(
                    "no key column registered for schema '$cacheName'; " +
                    "registered: ${keyColumnByName.keys}"
                )
            val keyTuple = Tuple.create().set(keyColumn, key)
            val value: Tuple? = table.keyValueView().get(null, keyTuple)
            ReadOutcome(success = true, value = value)
        } catch (e: Exception) {
            ReadOutcome(success = false, error = e)
        }
    }
```

NOTE: GG9's `KeyValueView.get(tx, key)` returns `null` when the key is absent. The wrapping `ReadOutcome(success = true, value = null)` matches the Plan 6 GG8 contract and the existing `Gg8KvTargetReadTest` expectations.

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Write the env-gated integration test**

`src/test/kotlin/com/gridgain/demo/datagen/target/Gg9KvTargetReadTest.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9KvTargetReadTest {

    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test"

    @Test
    fun `read returns a value previously written`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val key = 2002L
            val parent = LinkedHashMap<String, Any?>().apply { put("id", key); put("name", "hello") }
            val writeOutcome = target.write(BusinessEvent(parentRow = parent, childrenBySchema = emptyMap()))
            if (!writeOutcome.success) writeOutcome.error?.printStackTrace()
            val outcome = target.read(tableName, key)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "read failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
            assertThat(outcome.value).isNotNull
        }
    }

    @Test
    fun `read returns success-with-null for absent key`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val outcome = target.read(tableName, key = -999_999L)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "read failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
            assertThat(outcome.value).isNull()
        }
    }
}
```

- [ ] **Step 4: Run targeted + full suite (env vars optional)**

Same env-var pattern as Task 6. Without env vars, the class is skipped.

```bash
./gradlew test
```

Expected: green, 148 tests (both gg9 integration tests skipped without env vars).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(datagen): Gg9KvTarget.read via KeyValueView<Tuple, Tuple>.get

Plan 7 Task 7. Mirrors Gg8KvTarget.read contract: success-with-null for
absent keys; success-with-value for present keys; failure-with-error
on unexpected exceptions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: CLI dispatch — `is Gg9KvTargetSpec ->` branch in `cli/Main.kt`

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt`

`Main.kt` currently has a non-exhaustive `when (targetSpec)` that compiled in Plan 6 only because `Gg8KvTargetSpec` was the sole subtype. Once Task 2 lands, the `when` becomes non-exhaustive and Kotlin emits a warning or error. Add the matching branch.

- [ ] **Step 1: Add the dispatch branch**

In `cli/Main.kt`, replace the imports + the `target: Target = when (targetSpec)` block:

```kotlin
import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.config.Gg9KvTargetSpec
// ... (other imports unchanged) ...
import com.gridgain.demo.datagen.target.Gg8KvTarget
import com.gridgain.demo.datagen.target.Gg9KvTarget
```

```kotlin
        val target: Target = when (targetSpec) {
            is Gg8KvTargetSpec -> Gg8KvTarget(
                clusterName = targetSpec.clusterName,
                keyColumnByName = keyColumnByName,
                transactionScope = scenario.transactionScope,
            )
            is Gg9KvTargetSpec -> Gg9KvTarget(
                clusterName = targetSpec.clusterName,
                keyColumnByName = keyColumnByName,
                transactionScope = scenario.transactionScope,
            )
        }
```

- [ ] **Step 2: Verify compile and full suite**

```bash
./gradlew compileKotlin
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 148 tests green.

NOTE: At runtime, the CLI process must have either `ignite-core:8.9.18` (for gg8-kv) **OR** `ignite-client:9.1.3` (for gg9-kv) on its classpath — not both. Plan 7 does not change `Main.kt`'s classpath assembly logic; the consumer (operator running the CLI directly, or Plan 8's `DataGenerateTask` once F8 is closed) is responsible.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt
git commit -m "$(cat <<'EOF'
feat(datagen): cli/Main.kt dispatch branch for Gg9KvTargetSpec

Plan 7 Task 8. Closes the sealed-class exhaustiveness gap on the
Main.kt `when (targetSpec)` once Task 2 lands.

Note: only one of ignite-core (GG8) or ignite-client (GG9) may be on
the runtime classpath. Plan 8 follow-up F8 tracks the
DataGenerateTask classpath split.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Final review + roadmap update

**Files:** verification + docs.

- [ ] **Step 1: End-to-end smoke against a live GG9 cluster (manual)**

Pre-create a GG9 table:

```sql
CREATE ZONE IF NOT EXISTS data_gen_zone WITH STORAGE_PROFILES = 'default';
CREATE TABLE IF NOT EXISTS data_gen_test (
    id BIGINT PRIMARY KEY,
    name VARCHAR
) ZONE data_gen_zone;
```

Author a `data.yaml`:

```yaml
schema_version: 2
schemas:
  - name: data_gen_test
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
  - name: gg9-test
    kind: gg9-kv
    cluster_name: <your-gg9-cluster-name>
scenarios:
  - name: smoke
    target: gg9-test
    root_schemas: [data_gen_test]
    rate: { kind: constant, ops_per_second: 50 }
    duration: { kind: count, value: 100 }
    transaction_scope: business_event
    read_ratio: 0.10
```

Run via the CLI directly (since Plan 8's `DataGenerateTask` has not yet been updated for GG9 — F8):

```bash
java -cp <build-libs>:<ignite-client-9.1.3-jar>:<gg9-client-finder-jar>:<dependencies> \
  com.gridgain.demo.datagen.cli.Main \
  --data data.yaml --ops ops.yaml --scenario smoke \
  --cluster-endpoints client-endpoints.yaml \
  --output ./out
```

Inspect:
- `result.successCount` should be ~100.
- `result.errorCount` should be small.
- The `data_gen_test` table should now contain ~80–95 rows.
- A small fraction of operations were reads (`read_ratio = 0.10`).

- [ ] **Step 2: Run the full unit suite without env vars**

```bash
./gradlew clean test
```

Expected: 148 tests green; 5 env-gated integration tests skipped (3 GG8 from Plan 6 + 2 GG9 from Plan 7).

- [ ] **Step 3: Final cross-cutting review** via `superpowers:requesting-code-review`.

- [ ] **Step 4: Update `docs/superpowers/ROADMAP.md`**

In the **Current State** block, bump the test count and add a bullet:

```
- Write to a real GG9 cluster via `Gg9KvTarget` (lazy IgniteClient,
  KeyValueView<Tuple, Tuple>, optional transaction wrapping when
  `transaction_scope: business_event`).
```

Strike the **Plan 7 — GG9 KV Target** entry from the **Remaining Plans (not yet drafted)** section. Add an entry under **Open Follow-ups** for **F8 — Plan 8 `DataGenerateTask` classpath split for GG8 vs GG9** (verbatim text from the GG8/GG9 classpath conflict section above).

Bump the "Last updated" line.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/ROADMAP.md
git commit -m "$(cat <<'EOF'
docs(datagen): roadmap — Plan 7 complete; add F8 follow-up

Plan 7 Task 9. GG9 KV target landed; record F8 (Plan 8 classpath
split) as the only known gap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §3 GG9 KV target | Tasks 1, 5, 6, 7 |
| §3 transactions on GG9 KV target | Task 6 |
| §3 Target capability flags (gg9 row) | Tasks 3, 5 |
| §3 read execution (gg9 row) | Tasks 3, 7 |
| §6 cross-element: scenario-target compat (gg9 row) | Task 3 |
| §10 dependencies — `gg9-client-finder`, `ignite-client` | Task 1 |
| Project rule: rich error messages | Tasks 5, 6 |
| Project rule: reuse `gridgain-demo-client-utils` | Tasks 1, 5 |
| Project rule: no hierarchy collapse — sealed `TargetSpec` extended additively | Task 2 |

**Out of scope of this plan (deferred):**
- Provisioning emit + apply for GG9 (`CREATE ZONE`, `CREATE TABLE`, `COLOCATE BY`) — Plan 9. Until then, GG9 tables must be pre-created.
- F8 — `DataGenerateTask` (Plan 8) classpath split for GG8 vs GG9. Until F8, GG9 scenarios driven by the plugin will fail at classpath assembly time. The CLI can be invoked directly with the right jars (smoke step above).
- F5 — `BusinessEvent.parentSchemaName` extension to remove the heuristic schema lookup in both `Gg8KvTarget.write` and `Gg9KvTarget.write`. Plan 7 inherits the heuristic; the fix benefits both targets equally and is best done as its own refactor.

---

## Critical files (forward references for Plan 8 follow-up + Plan 9)

- `target/Gg9KvTarget.kt` — the file to point at when wiring GG9 SQL provisioning in Plan 9 (it can re-create tables idempotently).
- `cli/Main.kt` — the dispatch site that, once F8 is closed, lets the plugin run GG9 scenarios end-to-end.
- `gg9-client-finder/DemoAddressFinder.java` — third-party (well, sibling-project) interface that the data generator depends on. Stable as of `0.0.5-SNAPSHOT`.
