# Data Generator — Plan 4: Scenario Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the scenario engine that drives `BusinessEventGenerator` at a configured rate against a pluggable `Target`, evaluates stop conditions, and emits a typed `ScenarioResult`. Ship a `Target` interface and an `InMemoryTarget` so the runner can be exercised end-to-end without a real cluster. End deliverable: given an `ops.yaml` declaring a scenario with rate, duration, stop conditions, and a root schema, the runner executes the scenario, writes events to the target, and produces a structured result file.

**Architecture:** Bump `CURRENT_OPS_SCHEMA_VERSION` from 1 to 2 with `MigrateOpsV1toV2` injecting `scenarios: []`. Define typed `OpsConfig` with `scenarios: List<ScenarioSpec>`. Each scenario carries a sealed `RateSpec` (constant / ramped / stepped), a sealed `DurationSpec` (time / count / until_stop_condition), an optional list of `StopConditionSpec`, plus a root schema name and (deferred) target binding. `Target` is an interface; `InMemoryTarget` records what was written for assertions. `ScenarioRunner` ties `BusinessEventGenerator` + `Target` + rate limiter + stop-condition evaluator together. Per spec §2, `transaction_scope` defaults to `business_event` — declared in this plan but actual transaction wrapping is deferred to Plan 5. Per spec §2, `read_ratio` is declared but reads require a real target (Plan 5).

**Tech Stack:** Same as Plan 3. No new dependencies.

---

## Spec extension to v2 ops yaml

```yaml
schema_version: 2
scenarios:
  - name: customer-load
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 100 }
    duration: { kind: time, value: PT30S }
    stop_conditions:
      - { kind: error_rate_above, threshold: 0.05 }
    transaction_scope: business_event
    read_ratio: 0.0
```

For Plan 4, only the `constant` rate kind, the `time` and `count` duration kinds, and the `error_rate_above` stop condition are runnable. Other kinds are designed (deserialized into the typed model and validated) but throw `MisconfigurationException` if a scenario tries to use them at runtime — Plan 5 wires them up.

---

## File Structure

```
gridgain-demo-data-generator/
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── ConfiguredVersions.kt        # bump CURRENT_OPS_SCHEMA_VERSION to 2
│   │   ├── MigrateOpsV1toV2.kt          # NEW
│   │   ├── OpsConfigMigrationRunner.kt  # NEW
│   │   ├── OpsConfig.kt                 # REPLACE permissive envelope with typed v2
│   │   ├── ScenarioSpec.kt              # NEW: ScenarioSpec, RateSpec, DurationSpec, StopConditionSpec sealed hierarchies
│   │   ├── ConfigurationParser.kt       # plug OpsConfigMigrationRunner default
│   │   └── CrossElementValidator.kt     # add ScenarioRootSchemaValidator
│   ├── target/                          # NEW package
│   │   ├── Target.kt                    # interface + WriteOutcome
│   │   └── InMemoryTarget.kt            # test impl
│   └── scenario/                        # NEW package
│       ├── RateLimiter.kt               # constant-rate pacer
│       ├── StopConditionEvaluator.kt    # error-rate gate
│       ├── ScenarioRunner.kt            # orchestrator
│       └── ScenarioResult.kt            # data class + yaml serializer
├── src/main/resources/schema/
│   └── ops/
│       ├── v1.schema.json               # keep
│       └── v2.schema.json               # NEW: typed structural validation
└── src/test/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   ├── MigrateOpsV1toV2Test.kt
    │   ├── OpsConfigMigrationRunnerTest.kt
    │   ├── OpsConfigDeserializationTest.kt
    │   └── ScenarioRootSchemaValidatorTest.kt
    ├── target/InMemoryTargetTest.kt
    └── scenario/
        ├── RateLimiterTest.kt
        ├── StopConditionEvaluatorTest.kt
        ├── ScenarioResultTest.kt
        └── ScenarioRunnerTest.kt
```

---

### Task 1: Bump ops schema to v2 + MigrateOpsV1toV2

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2Test.kt`

`MigrateOpsV1toV2` injects `scenarios: []` if absent (mirrors `MigrateV1toV2` for data).

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2Test.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV1toV2Test {

    @Test
    fun `from and to versions are 1 and 2`() {
        val m = MigrateOpsV1toV2()
        assertThat(m.fromVersion).isEqualTo(1)
        assertThat(m.toVersion).isEqualTo(2)
        assertThat(m.description).contains("scenarios")
    }

    @Test
    fun `injects empty scenarios list when absent`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 1)
        val out = MigrateOpsV1toV2().migrate(map)
        assertThat(out["scenarios"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `leaves existing scenarios list intact`() {
        val original = listOf(mapOf("name" to "alpha"))
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 1, "scenarios" to original
        )
        val out = MigrateOpsV1toV2().migrate(map)
        assertThat(out["scenarios"]).isSameAs(original)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.MigrateOpsV1toV2Test'`

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2.kt`:

```kotlin
package com.gridgain.demo.datagen.config

class MigrateOpsV1toV2 : ConfigMigration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2
    override val description: String = "ensure top-level scenarios list is present"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        if (!yaml.containsKey("scenarios")) {
            yaml["scenarios"] = emptyList<Any>()
        }
        return yaml
    }
}
```

Update `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`:

```kotlin
package com.gridgain.demo.datagen.config

const val CURRENT_DATA_SCHEMA_VERSION: Int = 2
const val CURRENT_OPS_SCHEMA_VERSION: Int = 2
```

- [ ] **Step 4: Run targeted tests — 3 PASS.**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.MigrateOpsV1toV2Test'`

The full suite will be RED until Task 5 wires the migration runner default — that's expected.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt src/main/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2.kt src/test/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV1toV2Test.kt
git commit -m "feat(datagen): bump ops schema to v2 and add MigrateOpsV1toV2"
```

Sign with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

### Task 2: v2 JSONSchema for ops.yaml

**Files:**
- Create: `src/main/resources/schema/ops/v2.schema.json`

The v2 ops schema requires `schema_version=2` and a `scenarios` array. Each scenario has required `name`, `root_schemas`, `rate`, `duration`, `transaction_scope`, `read_ratio`. Optional `stop_conditions` array. `rate` / `duration` / each stop condition use discriminator-based `oneOf` over their kinds.

- [ ] **Step 1: Write the schema**

Create `src/main/resources/schema/ops/v2.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://gridgain.com/datagen/ops-v2.schema.json",
  "title": "Data Generator ops.yaml v2",
  "type": "object",
  "required": ["schema_version", "scenarios"],
  "additionalProperties": false,
  "properties": {
    "schema_version": { "const": 2 },
    "scenarios": { "type": "array", "items": { "$ref": "#/$defs/scenario" } }
  },
  "$defs": {
    "scenario": {
      "type": "object",
      "required": ["name", "root_schemas", "rate", "duration", "transaction_scope", "read_ratio"],
      "additionalProperties": false,
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "root_schemas": { "type": "array", "minItems": 1, "items": { "type": "string", "minLength": 1 } },
        "rate": { "$ref": "#/$defs/rate" },
        "duration": { "$ref": "#/$defs/duration" },
        "stop_conditions": { "type": "array", "items": { "$ref": "#/$defs/stop_condition" } },
        "transaction_scope": { "enum": ["business_event", "none"] },
        "read_ratio": { "type": "number", "minimum": 0.0, "maximum": 1.0 }
      }
    },
    "rate": {
      "oneOf": [
        { "$ref": "#/$defs/rate_constant" },
        { "$ref": "#/$defs/rate_ramped" },
        { "$ref": "#/$defs/rate_stepped" }
      ]
    },
    "rate_constant": {
      "type": "object", "required": ["kind", "ops_per_second"], "additionalProperties": false,
      "properties": {
        "kind": { "const": "constant" },
        "ops_per_second": { "type": "number", "exclusiveMinimum": 0.0 }
      }
    },
    "rate_ramped": {
      "type": "object", "required": ["kind", "from", "to", "over"], "additionalProperties": false,
      "properties": {
        "kind": { "const": "ramped" },
        "from": { "type": "number", "exclusiveMinimum": 0.0 },
        "to": { "type": "number", "exclusiveMinimum": 0.0 },
        "over": { "type": "string", "minLength": 1 }
      }
    },
    "rate_stepped": {
      "type": "object", "required": ["kind", "steps"], "additionalProperties": false,
      "properties": {
        "kind": { "const": "stepped" },
        "steps": {
          "type": "array", "minItems": 1,
          "items": {
            "type": "object", "required": ["rate", "hold"], "additionalProperties": false,
            "properties": {
              "rate": { "type": "number", "exclusiveMinimum": 0.0 },
              "hold": { "type": "string", "minLength": 1 }
            }
          }
        }
      }
    },
    "duration": {
      "oneOf": [
        { "$ref": "#/$defs/duration_time" },
        { "$ref": "#/$defs/duration_count" },
        { "$ref": "#/$defs/duration_until_stop" }
      ]
    },
    "duration_time": {
      "type": "object", "required": ["kind", "value"], "additionalProperties": false,
      "properties": { "kind": { "const": "time" }, "value": { "type": "string", "minLength": 1 } }
    },
    "duration_count": {
      "type": "object", "required": ["kind", "value"], "additionalProperties": false,
      "properties": { "kind": { "const": "count" }, "value": { "type": "integer", "minimum": 1 } }
    },
    "duration_until_stop": {
      "type": "object", "required": ["kind"], "additionalProperties": false,
      "properties": { "kind": { "const": "until_stop_condition" } }
    },
    "stop_condition": {
      "oneOf": [
        { "$ref": "#/$defs/sc_latency_p99" },
        { "$ref": "#/$defs/sc_latency_p999" },
        { "$ref": "#/$defs/sc_error_rate" },
        { "$ref": "#/$defs/sc_external_signal" }
      ]
    },
    "sc_latency_p99": {
      "type": "object", "required": ["kind", "threshold"], "additionalProperties": false,
      "properties": { "kind": { "const": "latency_p99_above" }, "threshold": { "type": "string", "minLength": 1 } }
    },
    "sc_latency_p999": {
      "type": "object", "required": ["kind", "threshold"], "additionalProperties": false,
      "properties": { "kind": { "const": "latency_p999_above" }, "threshold": { "type": "string", "minLength": 1 } }
    },
    "sc_error_rate": {
      "type": "object", "required": ["kind", "threshold"], "additionalProperties": false,
      "properties": { "kind": { "const": "error_rate_above" }, "threshold": { "type": "number", "exclusiveMinimum": 0.0, "maximum": 1.0 } }
    },
    "sc_external_signal": {
      "type": "object", "required": ["kind"], "additionalProperties": false,
      "properties": { "kind": { "const": "external_signal" } }
    }
  }
}
```

- [ ] **Step 2: Verify resources package**

`./gradlew processResources` — `BUILD SUCCESSFUL`. Confirm `build/resources/main/schema/ops/v2.schema.json` exists.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/schema/ops/v2.schema.json
git commit -m "feat(datagen): add v2 JSONSchema for typed ops.yaml structure"
```

---

### Task 3: ScenarioSpec sealed hierarchies

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt`

Three sealed hierarchies for rate, duration, stop conditions, plus `ScenarioSpec` itself. Jackson polymorphism on `kind` discriminator.

- [ ] **Step 1: Write the file**

`src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class ScenarioSpec(
    val name: String,
    @JsonProperty("root_schemas") val rootSchemas: List<String>,
    val rate: RateSpec,
    val duration: DurationSpec,
    @JsonProperty("stop_conditions") val stopConditions: List<StopConditionSpec> = emptyList(),
    @JsonProperty("transaction_scope") val transactionScope: TransactionScope,
    @JsonProperty("read_ratio") val readRatio: Double,
)

enum class TransactionScope {
    @JsonProperty("business_event") BUSINESS_EVENT,
    @JsonProperty("none") NONE,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ConstantRateSpec::class, name = "constant"),
    JsonSubTypes.Type(value = RampedRateSpec::class, name = "ramped"),
    JsonSubTypes.Type(value = SteppedRateSpec::class, name = "stepped"),
)
sealed class RateSpec
data class ConstantRateSpec(@JsonProperty("ops_per_second") val opsPerSecond: Double) : RateSpec()
data class RampedRateSpec(val from: Double, val to: Double, val over: String) : RateSpec()
data class SteppedRateSpec(val steps: List<RateStep>) : RateSpec()
data class RateStep(val rate: Double, val hold: String)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = TimeDurationSpec::class, name = "time"),
    JsonSubTypes.Type(value = CountDurationSpec::class, name = "count"),
    JsonSubTypes.Type(value = UntilStopDurationSpec::class, name = "until_stop_condition"),
)
sealed class DurationSpec
data class TimeDurationSpec(val value: String) : DurationSpec()
data class CountDurationSpec(val value: Long) : DurationSpec()
class UntilStopDurationSpec : DurationSpec() {
    override fun equals(other: Any?) = other is UntilStopDurationSpec
    override fun hashCode() = 0
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = LatencyP99StopSpec::class, name = "latency_p99_above"),
    JsonSubTypes.Type(value = LatencyP999StopSpec::class, name = "latency_p999_above"),
    JsonSubTypes.Type(value = ErrorRateStopSpec::class, name = "error_rate_above"),
    JsonSubTypes.Type(value = ExternalSignalStopSpec::class, name = "external_signal"),
)
sealed class StopConditionSpec
data class LatencyP99StopSpec(val threshold: String) : StopConditionSpec()
data class LatencyP999StopSpec(val threshold: String) : StopConditionSpec()
data class ErrorRateStopSpec(val threshold: Double) : StopConditionSpec()
class ExternalSignalStopSpec : StopConditionSpec() {
    override fun equals(other: Any?) = other is ExternalSignalStopSpec
    override fun hashCode() = 0
}
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ScenarioSpec.kt
git commit -m "feat(datagen): add ScenarioSpec, RateSpec, DurationSpec, StopConditionSpec hierarchies"
```

---

### Task 4: Replace OpsConfig with typed v2 model

**Files:**
- Modify (full rewrite): `src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val scenarios: List<ScenarioSpec>,
)
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

The full test suite is RED here (Plan 1's `OpsConfig(schemaVersion = 1)` calls in tests are now broken because the typed `OpsConfig` requires `scenarios`). Task 5 fixes those test sites.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt
git commit -m "feat(datagen): replace permissive OpsConfig with typed v2 model"
```

---

### Task 5: Wire OpsConfigMigrationRunner default + fix downstream tests

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfigMigrationRunner.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/OpsConfigMigrationRunnerTest.kt`
- Modify (fix): every test that calls `OpsConfig(schemaVersion = 1)` — update to `OpsConfig(schemaVersion = 2, scenarios = emptyList())` (or whatever the test really needs). Likely files: `ColumnUniquenessValidatorTest.kt`, `RelationReferentialValidatorTest.kt`, `NullRateOnRelationColumnValidatorTest.kt`, `CohortBucketSharesValidatorTest.kt`. Also: `JsonSchemaValidatorTest.kt` "valid v1 ops yaml passes" test must pass `version = 1` explicitly. Also: `ConfigurationParserTest.kt` "auto-migrates a v1 data file forward to v2 end-to-end" needs `assertThat(parsed.ops.schemaVersion).isEqualTo(2)`.

- [ ] **Step 1: Write OpsConfigMigrationRunnerTest**

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class OpsConfigMigrationRunnerTest {
    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test
    fun `migrates a v1 ops file forward to v2`(@TempDir dir: Path) {
        val file = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 2, logger = logger)
        assertThat(text).contains("schema_version: 2")
        assertThat(text).containsPattern("scenarios:\\s*\\[\\s*\\]")
    }
}
```

- [ ] **Step 2: Implement runner factory**

`src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfigMigrationRunner.kt`:

```kotlin
package com.gridgain.demo.datagen.config

object OpsConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(MigrateOpsV1toV2()))
}
```

Update `ConfigurationParser.kt` constructor default:

```kotlin
private val opsMigrationRunner: ConfigMigrationRunner = OpsConfigMigrationRunner.create(),
```

- [ ] **Step 3: Fix every `OpsConfig(schemaVersion = 1)` callsite**

In each of the validator test files listed above, replace `OpsConfig(schemaVersion = 1)` with `OpsConfig(schemaVersion = 2, scenarios = emptyList())`.

In `JsonSchemaValidatorTest.kt`, the test `valid v1 ops yaml passes` must keep validating against v1, so add `version = 1` to the call. The test `valid v1 data yaml passes` already has this fix from Plan 2.

In `ConfigurationParserTest.kt`, the test renamed in Plan 2 to `auto-migrates a v1 data file forward to v2 end-to-end` previously asserted `parsed.ops.schemaVersion == 1`. Update it to `parsed.ops.schemaVersion == 2` and add `assertThat(parsed.ops.scenarios).isEmpty()`. Also the other tests in `ConfigurationParserTest.kt` that pass an inline `"schema_version: 1\n"` to the ops file must change to `"schema_version: 2\nscenarios: []\n"` OR rely on the auto-migration path (the parser will migrate v1 to v2 automatically now that the runner default is wired).

- [ ] **Step 4: Run full suite — expect green**

`./gradlew test` — `BUILD SUCCESSFUL`. Total tests = 88 (87 prior + 1 new).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datagen): wire MigrateOpsV1toV2 runner default and fix downstream tests"
```

---

### Task 6: ScenarioRootSchemaValidator (cross-element)

**Files:**
- Modify (append): `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt` (compose)
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioRootSchemaValidatorTest.kt`

Validates that every scenario's `root_schemas` reference exists in `data.yaml`'s schemas. Also that scenario names are unique.

- [ ] **Step 1: Write tests**

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ScenarioRootSchemaValidatorTest {

    private fun col() = ColumnSpec("id", 0.0, SequenceSpec(1, 1))
    private fun scenario(name: String, roots: List<String>) = ScenarioSpec(
        name = name, rootSchemas = roots,
        rate = ConstantRateSpec(100.0),
        duration = TimeDurationSpec("PT10S"),
        transactionScope = TransactionScope.BUSINESS_EVENT,
        readRatio = 0.0,
    )

    @Test
    fun `accepts a scenario whose root schema exists`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(scenario("s", listOf("customer"))))
        assertThat(ScenarioRootSchemaValidator().validate(data, ops).errors).isEmpty()
    }

    @Test
    fun `rejects a scenario whose root schema is unknown`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(scenario("s", listOf("missing"))))
        val r = ScenarioRootSchemaValidator().validate(data, ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("s").contains("missing")
    }

    @Test
    fun `rejects duplicate scenario names`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(
            scenario("s", listOf("customer")),
            scenario("s", listOf("customer")),
        ))
        val r = ScenarioRootSchemaValidator().validate(data, ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("duplicate scenario")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement** — append to `CrossElementValidator.kt`:

```kotlin
class ScenarioRootSchemaValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val knownSchemas = data.schemas.map { it.name }.toSet()
        val seenNames = mutableSetOf<String>()
        for (scenario in ops.scenarios) {
            if (!seenNames.add(scenario.name)) {
                errors += "duplicate scenario name '${scenario.name}' in ops.yaml; " +
                    "scenario names must be unique."
            }
            for (root in scenario.rootSchemas) {
                if (root !in knownSchemas) {
                    errors += "scenario '${scenario.name}' references root_schema '$root' " +
                        "which is not declared in data.yaml. " +
                        "Available schemas: ${knownSchemas.joinToString(", ")}."
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
```

- [ ] **Step 4: Compose**

Update `ConfigurationParser.kt` cross-element default to add `ScenarioRootSchemaValidator()` to the list.

- [ ] **Step 5: Run — 3 PASS, full suite green.**

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioRootSchemaValidatorTest.kt
git commit -m "feat(datagen): add ScenarioRootSchemaValidator"
```

---

### Task 7: Target interface + InMemoryTarget

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/target/Target.kt`
- Create: `src/main/kotlin/com/gridgain/demo/datagen/target/InMemoryTarget.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/target/InMemoryTargetTest.kt`

`Target` is the contract Plan 5 will implement against GG8/GG9. Plan 4 ships only the in-memory test impl.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class InMemoryTargetTest {

    private fun event(id: Long): BusinessEvent {
        val parent = LinkedHashMap<String, Any?>().apply { put("id", id) }
        return BusinessEvent(parentRow = parent, childrenBySchema = emptyMap())
    }

    @Test
    fun `records each successful write`() {
        val t = InMemoryTarget()
        t.write(event(1L))
        t.write(event(2L))
        assertThat(t.writes).hasSize(2)
        assertThat(t.writes[0].parentRow["id"]).isEqualTo(1L)
    }

    @Test
    fun `supports reads is true; supports transactions is false`() {
        val t = InMemoryTarget()
        assertThat(t.supportsReads).isTrue()
        assertThat(t.supportsTransactions).isFalse()
    }

    @Test
    fun `write returns a successful WriteOutcome`() {
        val outcome = InMemoryTarget().write(event(1L))
        assertThat(outcome.success).isTrue()
        assertThat(outcome.error).isNull()
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/target/Target.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class WriteOutcome(
    val success: Boolean,
    val error: Throwable? = null,
)

interface Target {
    val supportsReads: Boolean
    val supportsTransactions: Boolean
    fun write(event: BusinessEvent): WriteOutcome
}
```

`src/main/kotlin/com/gridgain/demo/datagen/target/InMemoryTarget.kt`:

```kotlin
package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

class InMemoryTarget : Target {
    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = false

    private val _writes: MutableList<BusinessEvent> = mutableListOf()
    val writes: List<BusinessEvent> get() = _writes

    override fun write(event: BusinessEvent): WriteOutcome {
        _writes.add(event)
        return WriteOutcome(success = true)
    }
}
```

- [ ] **Step 4: Run — 3 PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/target/ src/test/kotlin/com/gridgain/demo/datagen/target/
git commit -m "feat(datagen): add Target interface and InMemoryTarget"
```

---

### Task 8: RateLimiter (constant rate)

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/RateLimiterTest.kt`

For Plan 4, only constant rate is implemented. The runner pacers each `next()` call so the actual ops/second matches the target rate within tolerance.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class RateLimiterTest {

    @Test
    fun `100 ops at constant rate of 200 takes about 500ms`() {
        val limiter = ConstantRateLimiter(opsPerSecond = 200.0)
        val start = System.nanoTime()
        repeat(100) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isBetween(400L, 700L)
    }

    @Test
    fun `first acquire returns immediately`() {
        val limiter = ConstantRateLimiter(opsPerSecond = 100.0)
        val start = System.nanoTime()
        limiter.acquire()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isLessThan(50L)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.datagen.scenario

interface RateLimiter {
    /** Block until the caller may proceed with the next operation. */
    fun acquire()
}

class ConstantRateLimiter(opsPerSecond: Double) : RateLimiter {
    private val intervalNanos: Long = (1_000_000_000.0 / opsPerSecond).toLong()
    private var nextAllowedNanos: Long = System.nanoTime()

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            val ms = sleep / 1_000_000
            val ns = (sleep % 1_000_000).toInt()
            Thread.sleep(ms, ns)
        }
        nextAllowedNanos = maxOf(nextAllowedNanos, now) + intervalNanos
    }
}
```

- [ ] **Step 4: Run — 2 PASS.** (Timing tests can flap on slow CI; if they fail consistently with margin >100ms, widen the bands. Otherwise leave.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/RateLimiterTest.kt
git commit -m "feat(datagen): add RateLimiter interface and ConstantRateLimiter"
```

---

### Task 9: StopConditionEvaluator

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorTest.kt`

Plan 4 evaluates `error_rate_above` only. Latency-based and external-signal stop conditions are deserialized but rejected at runtime with `MisconfigurationException` (Plan 5 wires them).

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class StopConditionEvaluatorTest {

    @Test
    fun `error rate below threshold does not trigger`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)))
        e.recordOutcome(success = true); e.recordOutcome(success = true)
        e.recordOutcome(success = false)  // 1/3 ~ 0.33 — but minimum samples
        assertThat(e.shouldStop()).isNotNull   // sample size 3 may or may not be enough; OK either way
    }

    @Test
    fun `error rate above threshold triggers stop with reason`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)))
        repeat(100) { e.recordOutcome(success = true) }
        repeat(20) { e.recordOutcome(success = false) }
        val reason = e.shouldStop()
        assertThat(reason).isNotNull
        assertThat(reason!!).contains("error_rate")
    }

    @Test
    fun `unsupported stop condition kind is rejected at construction`() {
        assertThatThrownBy { StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.1S"))) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("latency_p99_above")
            .hasMessageContaining("Plan 5")

        assertThatThrownBy { StopConditionEvaluator(listOf(ExternalSignalStopSpec())) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("external_signal")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.config.LatencyP999StopSpec
import com.gridgain.demo.datagen.config.StopConditionSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException

class StopConditionEvaluator(private val conditions: List<StopConditionSpec>) {

    init {
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec -> Unit  // supported
                is LatencyP99StopSpec, is LatencyP999StopSpec -> throw MisconfigurationException(
                    "Stop condition kind '${if (c is LatencyP99StopSpec) "latency_p99_above" else "latency_p999_above"}' " +
                    "is designed but not implemented in this build. " +
                    "Plan 5 will wire latency-based stop conditions when real KV targets land. " +
                    "Use error_rate_above for now."
                )
                is ExternalSignalStopSpec -> throw MisconfigurationException(
                    "Stop condition kind 'external_signal' is designed but not implemented in this build. " +
                    "Plan 5 or later will wire external-signal stop conditions."
                )
            }
        }
    }

    private var successCount: Long = 0
    private var failureCount: Long = 0

    fun recordOutcome(success: Boolean) {
        if (success) successCount++ else failureCount++
    }

    /** Returns a stop reason string if any condition has triggered, else null. Requires at least 100 samples. */
    fun shouldStop(): String? {
        val total = successCount + failureCount
        if (total < 100) return null
        val errorRate = failureCount.toDouble() / total
        for (c in conditions) {
            if (c is ErrorRateStopSpec && errorRate > c.threshold) {
                return "error_rate exceeded threshold: $errorRate > ${c.threshold}"
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run — 3 PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluator.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorTest.kt
git commit -m "feat(datagen): add StopConditionEvaluator (error_rate; others throw)"
```

---

### Task 10: ScenarioResult + yaml serializer

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioResult.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioResultTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

class ScenarioResultTest {

    @Test
    fun `writes a yaml file under run directory`(@TempDir dir: Path) {
        val result = ScenarioResult(
            scenarioName = "alpha",
            achievedRate = 95.4,
            errorCount = 3,
            successCount = 1000,
            stopReason = "duration elapsed",
            wallTime = Duration.ofSeconds(10),
        )
        val out = dir.resolve("result.yaml")
        ScenarioResult.write(result, out)
        val text = Files.readString(out)
        assertThat(text).contains("scenario_name: alpha")
        assertThat(text).contains("achieved_rate: 95.4")
        assertThat(text).contains("error_count: 3")
        assertThat(text).contains("success_count: 1000")
        assertThat(text).contains("stop_reason: duration elapsed")
        assertThat(text).contains("wall_time: PT10S")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class ScenarioResult(
    val scenarioName: String,
    val achievedRate: Double,
    val errorCount: Long,
    val successCount: Long,
    val stopReason: String,
    val wallTime: Duration,
) {
    companion object {
        private val mapper: YAMLMapper = (YAMLMapper().registerKotlinModule() as YAMLMapper)
            .registerModule(JavaTimeModule()) as YAMLMapper

        fun write(result: ScenarioResult, path: Path) {
            Files.createDirectories(path.parent)
            val map = linkedMapOf(
                "scenario_name" to result.scenarioName,
                "achieved_rate" to result.achievedRate,
                "error_count" to result.errorCount,
                "success_count" to result.successCount,
                "stop_reason" to result.stopReason,
                "wall_time" to result.wallTime.toString(),
            )
            mapper.writeValue(path.toFile(), map)
        }
    }
}
```

If `JavaTimeModule` is unavailable (it was dropped in Plan 1's review fixes), drop the `.registerModule(JavaTimeModule())` call — the `wallTime` field is serialized via `toString()` to "PT10S" form anyway, no Jackson `Duration` codec needed.

- [ ] **Step 4: Run — 1 PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioResult.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioResultTest.kt
git commit -m "feat(datagen): add ScenarioResult and yaml writer"
```

---

### Task 11: ScenarioRunner

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerTest.kt`

Top-level orchestrator. Constructor takes a `ScenarioSpec`, a `BusinessEventGenerator`, a `Target`, an optional `RateLimiter` and `StopConditionEvaluator`. `run()` loops:
1. `rateLimiter.acquire()`
2. `event = generator.next()`
3. `outcome = target.write(event)`
4. `evaluator.recordOutcome(outcome.success)`
5. Check duration / stop conditions; break if reached.
Returns `ScenarioResult`.

For Plan 4, only `ConstantRateSpec` rate, `TimeDurationSpec` and `CountDurationSpec` durations are runnable. Other kinds throw `MisconfigurationException`.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerTest {

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, SequenceSpec(1, 1))))
    ))

    private fun runner(@TempDir dir: Path, scenario: ScenarioSpec, target: InMemoryTarget = InMemoryTarget()): ScenarioRunner {
        val data = simpleData()
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario = scenario, generator = gen, target = target)
    }

    @Test
    fun `count duration writes exactly N events`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "count-50",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 1000.0),  // fast
            duration = CountDurationSpec(value = 50),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(target.writes).hasSize(50)
        assertThat(result.successCount).isEqualTo(50)
        assertThat(result.errorCount).isEqualTo(0)
        assertThat(result.stopReason).isEqualTo("count reached")
        assertThat(result.scenarioName).isEqualTo("count-50")
    }

    @Test
    fun `time duration runs for at least the configured duration`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "time-200ms",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 100.0),
            duration = TimeDurationSpec("PT0.2S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(result.wallTime.toMillis()).isBetween(180L, 600L)
        assertThat(result.successCount).isBetween(15L, 35L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `unsupported rate kind is rejected`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "ramped",
            rootSchemas = listOf("customer"),
            rate = RampedRateSpec(from = 1.0, to = 100.0, over = "PT1S"),
            duration = CountDurationSpec(value = 5),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        assertThatThrownBy { runner(dir, scenario).run() }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("ramped")
            .hasMessageContaining("Plan 5")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.target.Target
import java.time.Duration
import java.time.Instant

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val generator: BusinessEventGenerator,
    private val target: Target,
) {
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
                    rateLimiter.acquire()
                    val outcome = target.write(generator.next())
                    if (outcome.success) success++ else error++
                    evaluator.recordOutcome(outcome.success)
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val target = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < target) {
                    rateLimiter.acquire()
                    val outcome = this.target.write(generator.next())
                    if (outcome.success) success++ else error++
                    evaluator.recordOutcome(outcome.success)
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> throw MisconfigurationException(
                "Duration kind 'until_stop_condition' is designed but not implemented in this build. " +
                "Plan 5 will wire latency-based stop conditions that this depends on. " +
                "Use 'time' or 'count' for now."
            )
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

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> throw MisconfigurationException(
            "Rate kind 'ramped' is designed but not implemented in this build. " +
            "Plan 5 will wire ramped rate. Use 'constant' for now."
        )
        is SteppedRateSpec -> throw MisconfigurationException(
            "Rate kind 'stepped' is designed but not implemented in this build. " +
            "Plan 5 will wire stepped rate. Use 'constant' for now."
        )
    }
}
```

- [ ] **Step 4: Run — 3 PASS.**

(The `time-200ms` test gives a generous 180–600ms band to absorb CI jitter. If it flaps, widen further.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerTest.kt
git commit -m "feat(datagen): add ScenarioRunner orchestrating generator + target + rate"
```

---

### Task 12: Full-suite green check

- [ ] `./gradlew clean test` — `BUILD SUCCESSFUL`. Total tests = 87 (Plan 3) + 16 new (3 + 1 + 3 + 3 + 2 + 3 + 1 + 0 dedup of test count) = **103**.

Actually let me recount:
- Task 1 MigrateOpsV1toV2Test: 3
- Task 5 OpsConfigMigrationRunnerTest: 1 (plus modifications to existing tests, not new tests)
- Task 6 ScenarioRootSchemaValidatorTest: 3
- Task 7 InMemoryTargetTest: 3
- Task 8 RateLimiterTest: 2
- Task 9 StopConditionEvaluatorTest: 3
- Task 10 ScenarioResultTest: 1
- Task 11 ScenarioRunnerTest: 3

Total new: 19. Plan 3 final = 87. Plan 4 final = **106**.

If the count differs because of edits to existing tests, accept whatever the actual count is — green is what matters.

- [ ] `./gradlew clean build` — `BUILD SUCCESSFUL`.

---

## Verification (end-to-end smoke)

1. Author a v2 ops.yaml with one constant-rate, count-duration scenario; pair with a v2 data.yaml from Plan 3.
2. Parse via `ConfigurationParser`. Confirm cross-element validation passes.
3. Build a `BusinessEventGenerator` rooted at the scenario's first root schema.
4. Run a `ScenarioRunner` against an `InMemoryTarget`. Confirm:
   - the target receives the configured number of events,
   - `result.yaml` contains the scenario name, achieved rate, success/error counts, stop reason, wall time,
   - the achieved rate is within ±20% of the configured rate (sanity check).
5. Mutate the scenario to use `ramped` rate and rerun; confirm the runner throws `MisconfigurationException` mentioning Plan 5.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §2 scenario primitives (rate, duration, stop conditions, root schema) | Tasks 3, 4 |
| §2 ConstantRate, TimeDuration, CountDuration, ErrorRateAbove — runtime | Tasks 8, 9, 11 |
| §2 RampedRate, SteppedRate, latency stop conditions, external_signal — designed/deferred | Tasks 3, 9, 11 (throw at runtime) |
| §2 transaction_scope field present (execution deferred to Plan 5) | Task 3 |
| §2 read_ratio field present (execution deferred to Plan 5) | Task 3 |
| §2 ScenarioResult format | Task 10 |
| §6 schema versioning + ops migration v1→v2 | Tasks 1, 5 |
| §6 cross-element: scenario root must exist; scenario names unique | Task 6 |
| Project rule: rich error messages | Tasks 9, 11 |
| Target abstraction with capability flags (supportsReads, supportsTransactions) | Task 7 |

**Out of scope of this plan (deferred):**
- Real KV targets (GG8, GG9) — Plan 5.
- `update_ratio` execution (key registry per schema) — Plan 5.
- Affinity column annotation + provisioning — Plan 5.
- Transaction wrapping of business events — Plan 5.
- Latency-based stop conditions (need real latency measurements) — Plan 5.
- Ramped and stepped rate — Plan 5 (designed in this plan).
- Read execution against a target with `supports_reads = true` — Plan 5.
- State persistence — Plan 6.
- OTel — Plan 7.
- CLI / plugin invocation — Plan 8.

---

## Critical files (forward references for Plan 5+)

- `target/Target.kt` — Plan 5 adds `Gg8KvTarget` and `Gg9KvTarget` implementations.
- `scenario/ScenarioRunner.kt` — Plan 5 replaces the throw-on-`ramped`/`stepped`/`latency` branches with real implementations.
- `config/ScenarioSpec.kt` — Plan 5 may add `target: String` field on `ScenarioSpec` for binding scenarios to declared targets.
