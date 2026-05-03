# Data Generator — Plan 2: Data Shape & Per-Row Generation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Plan 1's permissive `DataConfig` envelope with a typed v2 schema/column/value-source model, and implement a per-row `RowGenerator` that produces values via a pluggable `ValueSource` contract. Wire DataFaker for stock providers and ship five built-in value-source kinds. End deliverable: given a v2 `data.yaml`, the generator can emit a stream of rows for any declared schema.

**Architecture:** A typed sealed hierarchy `ValueSourceSpec` (deserialized via Jackson polymorphism on the `kind` discriminator) drives a parallel runtime hierarchy `ValueSource` (constructed by a factory). Each schema carries its own `RowGenerator` that combines its columns' value sources, applies `null_rate` post-generation, and produces a `Map<String, Any?>`. A `GenerationContext` is threaded through every `ValueSource.next()` call so Plan 3 can extend it (rowSoFar, parentRow) without changing the contract. Cohort sampling, parent-FK relations, key-suffix, and `update_ratio` execution are intentionally deferred to Plan 3 because they require cross-row state.

**Tech Stack:**
- DataFaker 2.5.4 (already on classpath)
- Jackson 2.17.2 polymorphism (`@JsonTypeInfo` + `@JsonSubTypes`)
- networknt JSONSchema validator 1.5.9 (already on classpath)
- All Plan 1 foundation (`ConfigMigrationRunner`, `JsonSchemaValidator`, `ConfigurationParser`, `MisconfigurationException`)

---

## File Structure

```
gridgain-demo-data-generator/
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── ConfiguredVersions.kt           # bump CURRENT_DATA_SCHEMA_VERSION to 2
│   │   ├── DataConfig.kt                   # REPLACE permissive envelope with typed v2
│   │   ├── ValueSourceSpec.kt              # sealed hierarchy (NEW)
│   │   ├── MigrateV1toV2.kt                # NEW
│   │   ├── DataConfigMigrationRunner.kt    # NEW: registers v1->v2 migration
│   │   ├── ConfigurationParser.kt          # plug DataConfigMigrationRunner in by default
│   │   └── CrossElementValidator.kt        # add ColumnUniquenessValidator
│   ├── generation/                         # NEW package
│   │   ├── ValueSource.kt                  # interface + GenerationContext
│   │   ├── ValueSourceFactory.kt           # spec → runtime
│   │   ├── DataFakerValueSource.kt
│   │   ├── SequenceValueSource.kt
│   │   ├── UniqueValueSource.kt
│   │   ├── WeightedChoiceValueSource.kt
│   │   ├── YamlBackedValueSource.kt
│   │   ├── NullRateApplicator.kt
│   │   └── RowGenerator.kt
├── src/main/resources/schema/
│   └── data/
│       ├── v1.schema.json                  # keep — migration runner needs it for round-trip dump
│       └── v2.schema.json                  # NEW: typed structural validation
└── src/test/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   ├── MigrateV1toV2Test.kt            # NEW
    │   ├── DataConfigMigrationRunnerTest.kt# NEW
    │   ├── DataConfigDeserializationTest.kt# NEW
    │   ├── ColumnUniquenessValidatorTest.kt# NEW
    │   └── ConfigurationParserTest.kt      # extend with v2 happy-path test
    └── generation/
        ├── DataFakerValueSourceTest.kt
        ├── SequenceValueSourceTest.kt
        ├── UniqueValueSourceTest.kt
        ├── WeightedChoiceValueSourceTest.kt
        ├── YamlBackedValueSourceTest.kt
        ├── NullRateApplicatorTest.kt
        ├── ValueSourceFactoryTest.kt
        └── RowGeneratorTest.kt
```

---

## Configuration Shape (target v2 yaml)

```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.05
    columns:
      - name: id
        null_rate: 0.0
        value_source:
          kind: sequence
          start: 1
          step: 1
      - name: first_name
        null_rate: 0.02
        value_source:
          kind: datafaker
          expression: "#{name.firstName}"
      - name: state
        null_rate: 0.0
        value_source:
          kind: weighted-choice
          choices:
            - { value: "CA", weight: 0.40 }
            - { value: "NY", weight: 0.30 }
            - { value: "TX", weight: 0.30 }
      - name: zip
        null_rate: 0.0
        value_source:
          kind: yaml-data
          path: "data/us-zips.yaml"
          key: "zip_codes"
      - name: handle
        null_rate: 0.0
        value_source:
          kind: unique
          expression: "#{internet.username}"
```

All fields are required (no defaults on template classes — workspace project rule).

---

### Task 1: Bump data schema version to 2 + add v1→v2 migration

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2Test.kt`

`MigrateV1toV2` injects `schemas: []` if absent; otherwise leaves the file untouched (a v1 file may already carry typed-shaped fields the user authored ahead of v2 enforcement).

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2Test.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateV1toV2Test {

    @Test
    fun `from and to versions are 1 and 2`() {
        val m = MigrateV1toV2()
        assertThat(m.fromVersion).isEqualTo(1)
        assertThat(m.toVersion).isEqualTo(2)
        assertThat(m.description).contains("schemas")
    }

    @Test
    fun `injects empty schemas list when absent`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 1)
        val out = MigrateV1toV2().migrate(map)
        assertThat(out["schemas"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `leaves existing schemas list intact`() {
        val original = listOf(mapOf("name" to "customer"))
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 1,
            "schemas" to original,
        )
        val out = MigrateV1toV2().migrate(map)
        assertThat(out["schemas"]).isSameAs(original)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.MigrateV1toV2Test'` — `Unresolved reference: MigrateV1toV2`.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2.kt`:

```kotlin
package com.gridgain.demo.datagen.config

class MigrateV1toV2 : ConfigMigration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2
    override val description: String = "ensure top-level schemas list is present"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        if (!yaml.containsKey("schemas")) {
            yaml["schemas"] = emptyList<Any>()
        }
        return yaml
    }
}
```

Bump constant in `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`:

```kotlin
package com.gridgain.demo.datagen.config

const val CURRENT_DATA_SCHEMA_VERSION: Int = 2
const val CURRENT_OPS_SCHEMA_VERSION: Int = 1
```

- [ ] **Step 4: Run — expect 3 tests PASS**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.MigrateV1toV2Test'`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt src/main/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2.kt src/test/kotlin/com/gridgain/demo/datagen/config/MigrateV1toV2Test.kt
git commit -m "feat(datagen): bump data schema to v2 and add MigrateV1toV2"
```

Sign with the standard `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` trailer.

---

### Task 2: v2 JSONSchema for data.yaml

**Files:**
- Create: `src/main/resources/schema/data/v2.schema.json`

The v2 schema enforces structure: required `schemas`, each schema has required `name`/`update_ratio`/`columns`, each column has required `name`/`null_rate`/`value_source`, and `value_source` has a discriminator `kind` with a `oneOf` over the five kinds shipped in this plan.

- [ ] **Step 1: Write the schema file**

Create `src/main/resources/schema/data/v2.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://gridgain.com/datagen/data-v2.schema.json",
  "title": "Data Generator data.yaml v2",
  "type": "object",
  "required": ["schema_version", "schemas"],
  "properties": {
    "schema_version": { "const": 2 },
    "schemas": {
      "type": "array",
      "items": { "$ref": "#/$defs/schema" }
    }
  },
  "additionalProperties": false,
  "$defs": {
    "schema": {
      "type": "object",
      "required": ["name", "update_ratio", "columns"],
      "additionalProperties": false,
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "update_ratio": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "columns": {
          "type": "array",
          "minItems": 1,
          "items": { "$ref": "#/$defs/column" }
        }
      }
    },
    "column": {
      "type": "object",
      "required": ["name", "null_rate", "value_source"],
      "additionalProperties": false,
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "null_rate": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "value_source": { "$ref": "#/$defs/value_source" }
      }
    },
    "value_source": {
      "oneOf": [
        { "$ref": "#/$defs/vs_datafaker" },
        { "$ref": "#/$defs/vs_sequence" },
        { "$ref": "#/$defs/vs_unique" },
        { "$ref": "#/$defs/vs_weighted_choice" },
        { "$ref": "#/$defs/vs_yaml_data" }
      ]
    },
    "vs_datafaker": {
      "type": "object",
      "required": ["kind", "expression"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "datafaker" },
        "expression": { "type": "string", "minLength": 1 }
      }
    },
    "vs_sequence": {
      "type": "object",
      "required": ["kind", "start", "step"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "sequence" },
        "start": { "type": "integer" },
        "step": { "type": "integer" }
      }
    },
    "vs_unique": {
      "type": "object",
      "required": ["kind", "expression"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "unique" },
        "expression": { "type": "string", "minLength": 1 }
      }
    },
    "vs_weighted_choice": {
      "type": "object",
      "required": ["kind", "choices"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "weighted-choice" },
        "choices": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": ["value", "weight"],
            "additionalProperties": false,
            "properties": {
              "value": {},
              "weight": { "type": "number", "exclusiveMinimum": 0.0 }
            }
          }
        }
      }
    },
    "vs_yaml_data": {
      "type": "object",
      "required": ["kind", "path", "key"],
      "additionalProperties": false,
      "properties": {
        "kind": { "const": "yaml-data" },
        "path": { "type": "string", "minLength": 1 },
        "key": { "type": "string", "minLength": 1 }
      }
    }
  }
}
```

- [ ] **Step 2: Verify resource packaging**

Run `./gradlew processResources` and confirm `build/resources/main/schema/data/v2.schema.json` exists.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/schema/data/v2.schema.json
git commit -m "feat(datagen): add v2 JSONSchema for typed data.yaml structure"
```

---

### Task 3: ValueSourceSpec sealed hierarchy

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt`

Sealed Kotlin hierarchy with Jackson polymorphic deserialization on the `kind` discriminator.

- [ ] **Step 1: Write the file directly (deserialization tested in Task 5)**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = DataFakerSpec::class, name = "datafaker"),
    JsonSubTypes.Type(value = SequenceSpec::class, name = "sequence"),
    JsonSubTypes.Type(value = UniqueSpec::class, name = "unique"),
    JsonSubTypes.Type(value = WeightedChoiceSpec::class, name = "weighted-choice"),
    JsonSubTypes.Type(value = YamlDataSpec::class, name = "yaml-data"),
)
sealed class ValueSourceSpec

data class DataFakerSpec(val expression: String) : ValueSourceSpec()
data class SequenceSpec(val start: Long, val step: Long) : ValueSourceSpec()
data class UniqueSpec(val expression: String) : ValueSourceSpec()

data class WeightedChoice(val value: Any, val weight: Double)
data class WeightedChoiceSpec(val choices: List<WeightedChoice>) : ValueSourceSpec()

data class YamlDataSpec(val path: String, val key: String) : ValueSourceSpec()
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt
git commit -m "feat(datagen): add sealed ValueSourceSpec hierarchy with five kinds"
```

---

### Task 4: Replace DataConfig with typed v2 model

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt` (full rewrite)

Replaces the permissive `DataConfig` envelope with the typed v2 model. The v1 envelope is no longer needed at runtime — every parse is migrated to v2 first.

- [ ] **Step 1: Replace the file**

`src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class DataConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val schemas: List<SchemaSpec>,
)

data class SchemaSpec(
    val name: String,
    @JsonProperty("update_ratio") val updateRatio: Double,
    val columns: List<ColumnSpec>,
)

data class ColumnSpec(
    val name: String,
    @JsonProperty("null_rate") val nullRate: Double,
    @JsonProperty("value_source") val valueSource: ValueSourceSpec,
)
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

(The pre-existing `ConfigurationParserTest` will still pass — the minimal v1 fixture exercises migrate→v2 which adds `schemas: []`, which deserializes to `emptyList()`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt
git commit -m "feat(datagen): replace permissive DataConfig with typed v2 model"
```

---

### Task 5: Wire DataConfigMigrationRunner default into ConfigurationParser

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunner.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunnerTest.kt`

`DataConfigMigrationRunner` is a thin factory that returns a `ConfigMigrationRunner(listOf(MigrateV1toV2()))`. The parser's default for `dataMigrationRunner` becomes this factory's product.

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunnerTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class DataConfigMigrationRunnerTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test
    fun `migrates a v1 file forward to v2`(@TempDir dir: Path) {
        val file = dir.resolve("data.yaml").also { it.writeText("schema_version: 1\n") }
        val text = DataConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 2, logger = logger)
        assertThat(text).contains("schema_version: 2")
        assertThat(text).contains("schemas: []")
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`Unresolved reference: DataConfigMigrationRunner`).

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunner.kt`:

```kotlin
package com.gridgain.demo.datagen.config

object DataConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(MigrateV1toV2()))
}
```

Modify the default in `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt` constructor:

```kotlin
class ConfigurationParser(
    private val logger: DataGenLogger,
    private val dataMigrationRunner: ConfigMigrationRunner = DataConfigMigrationRunner.create(),
    private val opsMigrationRunner: ConfigMigrationRunner = ConfigMigrationRunner(emptyList()),
    private val crossElementValidator: CrossElementValidator = DefaultCrossElementValidator(),
) {
    // body unchanged
}
```

- [ ] **Step 4: Update Plan 1's existing parser test to match the new auto-migration default**

The Plan 1 test in `src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt` titled `parses minimal v1 data and ops yaml end-to-end` asserts `parsed.data.schemaVersion == 1`. After this change the v1 file is auto-migrated to v2 before deserialization, so the post-parse `schemaVersion` is now `2`. Rename the test and update the assertion:

```kotlin
@Test
fun `auto-migrates a v1 data file forward to v2 end-to-end`(@TempDir dir: Path) {
    val data = copyResource(dir, "data-v1-minimal.yaml", "data.yaml")
    val ops = copyResource(dir, "ops-v1-minimal.yaml", "ops.yaml")
    val parser = ConfigurationParser(logger = logger)
    val parsed = parser.parse(dataFile = data.toFile(), opsFile = ops.toFile())
    assertThat(parsed.data.schemaVersion).isEqualTo(2)
    assertThat(parsed.data.schemas).isEmpty()
    assertThat(parsed.ops.schemaVersion).isEqualTo(1)
}
```

- [ ] **Step 5: Run — DataConfigMigrationRunnerTest 1 test PASS, ConfigurationParserTest 5 tests PASS**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.DataConfigMigrationRunnerTest' --tests 'com.gridgain.demo.datagen.config.ConfigurationParserTest'`

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunner.kt src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigMigrationRunnerTest.kt src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt
git commit -m "feat(datagen): wire MigrateV1toV2 into the data migration runner default"
```

---

### Task 6: End-to-end deserialization test of typed DataConfig

**Files:**
- Test: `src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt`
- Test: `src/test/resources/data-v2-customer.yaml`

Confirms a populated v2 `data.yaml` deserializes through the full pipeline into `DataConfig(schemas, columns, value_source instances of every kind)`.

- [ ] **Step 1: Add fixture**

Create `src/test/resources/data-v2-customer.yaml`:

```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.05
    columns:
      - name: id
        null_rate: 0.0
        value_source:
          kind: sequence
          start: 1
          step: 1
      - name: first_name
        null_rate: 0.02
        value_source:
          kind: datafaker
          expression: "#{name.firstName}"
      - name: state
        null_rate: 0.0
        value_source:
          kind: weighted-choice
          choices:
            - { value: "CA", weight: 0.40 }
            - { value: "NY", weight: 0.30 }
            - { value: "TX", weight: 0.30 }
      - name: handle
        null_rate: 0.0
        value_source:
          kind: unique
          expression: "#{internet.username}"
      - name: zip
        null_rate: 0.0
        value_source:
          kind: yaml-data
          path: "data/us-zips.yaml"
          key: "zip_codes"
```

- [ ] **Step 2: Write failing test**

`src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class DataConfigDeserializationTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    private fun copy(@TempDir dir: Path, resource: String, name: String): Path {
        val target = dir.resolve(name)
        DataConfigDeserializationTest::class.java.classLoader
            .getResourceAsStream(resource).use { input ->
                requireNotNull(input)
                Files.copy(input, target)
            }
        return target
    }

    @Test
    fun `deserializes every value source kind`(@TempDir dir: Path) {
        val data = copy(dir, "data-v2-customer.yaml", "data.yaml")
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val parsed = ConfigurationParser(logger = logger)
            .parse(data.toFile(), ops.toFile())

        val schema = parsed.data.schemas.single()
        assertThat(schema.name).isEqualTo("customer")
        assertThat(schema.updateRatio).isEqualTo(0.05)
        assertThat(schema.columns.map { it.name })
            .containsExactly("id", "first_name", "state", "handle", "zip")

        assertThat(schema.columns[0].valueSource).isEqualTo(SequenceSpec(start = 1, step = 1))
        assertThat(schema.columns[1].valueSource).isEqualTo(DataFakerSpec(expression = "#{name.firstName}"))
        val state = schema.columns[2].valueSource as WeightedChoiceSpec
        assertThat(state.choices).containsExactly(
            WeightedChoice(value = "CA", weight = 0.40),
            WeightedChoice(value = "NY", weight = 0.30),
            WeightedChoice(value = "TX", weight = 0.30),
        )
        assertThat(schema.columns[3].valueSource).isEqualTo(UniqueSpec(expression = "#{internet.username}"))
        assertThat(schema.columns[4].valueSource).isEqualTo(YamlDataSpec(path = "data/us-zips.yaml", key = "zip_codes"))
    }
}
```

- [ ] **Step 3: Run — expect PASS** (every type already exists from Tasks 3–5).

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt src/test/resources/data-v2-customer.yaml
git commit -m "test(datagen): end-to-end deserialization for every v2 value source kind"
```

---

### Task 7: ColumnUniquenessValidator (cross-element)

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/ColumnUniquenessValidatorTest.kt`

First concrete cross-element rule: column names must be unique within each schema, and schema names must be unique within `data.yaml`.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/config/ColumnUniquenessValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ColumnUniquenessValidatorTest {

    private fun col(name: String) = ColumnSpec(
        name = name, nullRate = 0.0, valueSource = SequenceSpec(start = 1, step = 1)
    )

    @Test
    fun `accepts unique column names within unique schemas`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"), col("first_name"))),
                SchemaSpec("order", 0.0, listOf(col("id"), col("amount"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).isEmpty()
    }

    @Test
    fun `rejects duplicate column names within a schema`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"), col("id"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("id").contains("duplicate column")
    }

    @Test
    fun `rejects duplicate schema names`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"))),
                SchemaSpec("customer", 0.0, listOf(col("ref"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("duplicate schema")
    }
}
```

The test references `OpsConfig(schemaVersion = 1)`. Plan 1's `OpsConfig` is a permissive envelope and still accepts that constructor.

- [ ] **Step 2: Run — expect FAIL** (`Unresolved reference: ColumnUniquenessValidator`).

- [ ] **Step 3: Implement**

Append to `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`:

```kotlin
class ColumnUniquenessValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()

        val seenSchemas = mutableSetOf<String>()
        for (schema in data.schemas) {
            if (!seenSchemas.add(schema.name)) {
                errors += "duplicate schema name '${schema.name}' in data.yaml; " +
                    "schema names must be unique."
            }
            val seenColumns = mutableSetOf<String>()
            for (column in schema.columns) {
                if (!seenColumns.add(column.name)) {
                    errors += "duplicate column name '${column.name}' in schema '${schema.name}'; " +
                        "column names must be unique within a schema."
                }
            }
        }

        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
```

Update the parser default to compose with the new validator. Modify `ConfigurationParser` constructor default:

```kotlin
private val crossElementValidator: CrossElementValidator = CompositeCrossElementValidator(
    listOf(DefaultCrossElementValidator(), ColumnUniquenessValidator())
),
```

- [ ] **Step 4: Run — 3 tests PASS**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.ColumnUniquenessValidatorTest'`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt src/test/kotlin/com/gridgain/demo/datagen/config/ColumnUniquenessValidatorTest.kt
git commit -m "feat(datagen): add ColumnUniquenessValidator and compose into parser default"
```

---

### Task 8: ValueSource interface and GenerationContext

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt`

`GenerationContext` carries the shared `Faker` instance plus a placeholder for future row/parent context that Plan 3 fills in. The plan's stable contract is: every `ValueSource.next(ctx)` consults `ctx`, never global state.

- [ ] **Step 1: Write the file**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import net.datafaker.Faker

/**
 * The runtime contract for value generators. One instance per (schema, column).
 * Plan 3 will extend GenerationContext with rowSoFar/parentRow/etc; this contract is stable.
 */
interface ValueSource {
    fun next(ctx: GenerationContext): Any?
}

/**
 * Shared per-row context. Plan 2 only carries a Faker. Plan 3 will add cross-column and
 * cross-row fields without changing the interface.
 */
data class GenerationContext(
    val faker: Faker,
)
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt
git commit -m "feat(datagen): add ValueSource interface and GenerationContext"
```

---

### Task 9: SequenceValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSourceTest.kt`

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class SequenceValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `produces values starting at start with step`() {
        val s = SequenceValueSource(start = 10, step = 3)
        assertThat(s.next(ctx)).isEqualTo(10L)
        assertThat(s.next(ctx)).isEqualTo(13L)
        assertThat(s.next(ctx)).isEqualTo(16L)
    }

    @Test
    fun `negative step decrements`() {
        val s = SequenceValueSource(start = 0, step = -1)
        assertThat(s.next(ctx)).isEqualTo(0L)
        assertThat(s.next(ctx)).isEqualTo(-1L)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

class SequenceValueSource(start: Long, private val step: Long) : ValueSource {
    private var nextValue: Long = start
    override fun next(ctx: GenerationContext): Any {
        val out = nextValue
        nextValue += step
        return out
    }
}
```

- [ ] **Step 4: Run — 2 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSourceTest.kt
git commit -m "feat(datagen): add SequenceValueSource"
```

---

### Task 10: DataFakerValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSourceTest.kt`

Wraps DataFaker's `expression(String)` API for arbitrary expressions like `"#{name.firstName}"`.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class DataFakerValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `evaluates a simple datafaker expression`() {
        val s = DataFakerValueSource(expression = "#{name.firstName}")
        val v = s.next(ctx) as String
        assertThat(v).isNotEmpty()
    }

    @Test
    fun `produces stable string output across multiple calls`() {
        val s = DataFakerValueSource(expression = "#{name.firstName}")
        repeat(20) {
            val v = s.next(ctx)
            assertThat(v).isInstanceOf(String::class.java)
        }
    }

    @Test
    fun `wraps datafaker errors in MisconfigurationException with the expression`() {
        val s = DataFakerValueSource(expression = "#{not.a.real.provider}")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("not.a.real.provider")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class DataFakerValueSource(private val expression: String) : ValueSource {
    override fun next(ctx: GenerationContext): Any =
        try {
            ctx.faker.expression(expression)
        } catch (e: Exception) {
            throw MisconfigurationException(
                "DataFaker expression '$expression' could not be evaluated: ${e.message}. " +
                "Verify the expression references a known DataFaker provider.",
                cause = e,
            )
        }
}
```

- [ ] **Step 4: Run — 3 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/DataFakerValueSourceTest.kt
git commit -m "feat(datagen): add DataFakerValueSource wrapping faker.expression"
```

---

### Task 11: WeightedChoiceValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSourceTest.kt`

Custom implementation (DataFaker's weighted selection is documented as POC and not relied upon).

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class WeightedChoiceValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `single choice always returns that value`() {
        val s = WeightedChoiceValueSource(listOf(WeightedChoice("only", 1.0)), seed = 1L)
        repeat(10) { assertThat(s.next(ctx)).isEqualTo("only") }
    }

    @Test
    fun `weighted distribution converges to declared shares within tolerance`() {
        val s = WeightedChoiceValueSource(
            listOf(WeightedChoice("a", 0.7), WeightedChoice("b", 0.3)),
            seed = 42L,
        )
        val counts = mutableMapOf("a" to 0, "b" to 0)
        repeat(10_000) { counts.merge(s.next(ctx) as String, 1) { x, y -> x + y } }
        assertThat(counts["a"]!! / 10_000.0).isBetween(0.66, 0.74)
        assertThat(counts["b"]!! / 10_000.0).isBetween(0.26, 0.34)
    }

    @Test
    fun `empty choices is rejected at construction`() {
        assertThatThrownBy { WeightedChoiceValueSource(emptyList(), seed = 1L) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("at least one choice")
    }

    @Test
    fun `non-positive weight is rejected at construction`() {
        assertThatThrownBy {
            WeightedChoiceValueSource(listOf(WeightedChoice("x", 0.0)), seed = 1L)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("weight")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class WeightedChoiceValueSource(
    choices: List<WeightedChoice>,
    seed: Long,
) : ValueSource {

    init {
        if (choices.isEmpty()) {
            throw MisconfigurationException(
                "weighted-choice value source requires at least one choice; received zero. " +
                "Add at least one entry under 'choices'."
            )
        }
        choices.forEach {
            if (it.weight <= 0.0) {
                throw MisconfigurationException(
                    "weighted-choice value source has non-positive weight ${it.weight} for value '${it.value}'. " +
                    "Every choice must have a strictly positive weight."
                )
            }
        }
    }

    private val totalWeight: Double = choices.sumOf { it.weight }
    private val cumulative: List<Pair<Double, Any>> =
        choices.runningFold(0.0 to (Unit as Any)) { acc, c ->
            (acc.first + c.weight) to c.value
        }.drop(1)
    private val random: Random = Random(seed)

    override fun next(ctx: GenerationContext): Any {
        val r = random.nextDouble() * totalWeight
        return cumulative.first { r < it.first }.second
    }
}
```

- [ ] **Step 4: Run — 4 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/WeightedChoiceValueSourceTest.kt
git commit -m "feat(datagen): add WeightedChoiceValueSource with seedable RNG"
```

---

### Task 12: UniqueValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSourceTest.kt`

Tracks emitted values in a `Set` and retries the underlying `DataFakerValueSource` up to a limit on collision. Throws when retries are exhausted (matching the project's "no silent fallbacks" rule).

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class UniqueValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `produces distinct values across many calls`() {
        val s = UniqueValueSource(expression = "#{internet.username}", maxRetries = 100)
        val seen = (1..200).map { s.next(ctx) as String }.toSet()
        assertThat(seen.size).isEqualTo(200)
    }

    @Test
    fun `throws when the underlying space cannot satisfy uniqueness`() {
        // Tiny space: just two distinct options in a regex.
        val s = UniqueValueSource(expression = "#{regexify '[ab]'}", maxRetries = 5)
        s.next(ctx); s.next(ctx)
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("exhausted")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class UniqueValueSource(
    private val expression: String,
    private val maxRetries: Int,
) : ValueSource {

    private val emitted: MutableSet<Any?> = mutableSetOf()
    private val inner = DataFakerValueSource(expression)

    override fun next(ctx: GenerationContext): Any {
        repeat(maxRetries) {
            val candidate = inner.next(ctx)
            if (emitted.add(candidate)) return candidate
        }
        throw MisconfigurationException(
            "Unique value source for expression '$expression' exhausted after $maxRetries retries " +
            "(${emitted.size} unique values already emitted). " +
            "Either widen the expression's value space or stop demanding uniqueness for this column."
        )
    }
}
```

- [ ] **Step 4: Run — 2 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/UniqueValueSourceTest.kt
git commit -m "feat(datagen): add UniqueValueSource with retry exhaustion error"
```

---

### Task 13: YamlBackedValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSourceTest.kt`

Loads a yaml file lazily, picks values uniformly at random by `key` (which must resolve to a list inside the yaml mapping). Uses an injected `Random` for testability.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.writeText
import kotlin.test.Test

class YamlBackedValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `picks values from the named list`(@TempDir dir: Path) {
        val file = dir.resolve("colors.yaml").also {
            it.writeText("colors:\n  - red\n  - green\n  - blue\n")
        }
        val s = YamlBackedValueSource(file, key = "colors", random = Random(1L))
        val seen = (1..100).map { s.next(ctx) as String }.toSet()
        assertThat(seen).isSubsetOf("red", "green", "blue")
        assertThat(seen).hasSizeGreaterThan(1)
    }

    @Test
    fun `missing key produces remediation`(@TempDir dir: Path) {
        val file = dir.resolve("colors.yaml").also { it.writeText("colors:\n  - red\n") }
        val s = YamlBackedValueSource(file, key = "names", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("names")
            .hasMessageContaining(file.toString())
    }

    @Test
    fun `key whose value is not a list is rejected`(@TempDir dir: Path) {
        val file = dir.resolve("data.yaml").also { it.writeText("count: 5\n") }
        val s = YamlBackedValueSource(file, key = "count", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("count")
            .hasMessageContaining("list")
    }

    @Test
    fun `missing file produces remediation`(@TempDir dir: Path) {
        val s = YamlBackedValueSource(dir.resolve("absent.yaml"), key = "x", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("absent.yaml")
            .hasMessageContaining("does not exist")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

class YamlBackedValueSource(
    private val path: Path,
    private val key: String,
    private val random: Random,
) : ValueSource {

    private val values: List<Any?> by lazy { loadValues() }

    override fun next(ctx: GenerationContext): Any? = values[random.nextInt(values.size)]

    private fun loadValues(): List<Any?> {
        if (!Files.exists(path)) {
            throw MisconfigurationException(
                "yaml-data value source: file '$path' does not exist. " +
                "Verify the 'path' field on the column references a real yaml file."
            )
        }
        val text = Files.readString(path)
        val map = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Any?>(text) as? Map<String, Any?>
        } catch (e: Exception) {
            throw MisconfigurationException(
                "yaml-data value source: file '$path' is not valid YAML: ${e.message}.",
                cause = e,
            )
        }
            ?: throw MisconfigurationException(
                "yaml-data value source: file '$path' must be a yaml mapping at the top level."
            )

        if (!map.containsKey(key)) {
            throw MisconfigurationException(
                "yaml-data value source: key '$key' not found in '$path'. " +
                "Available keys: ${map.keys.joinToString(", ")}."
            )
        }
        val raw = map[key]
        if (raw !is List<*>) {
            throw MisconfigurationException(
                "yaml-data value source: key '$key' in '$path' must map to a list; " +
                "found ${raw?.javaClass?.simpleName ?: "null"} instead."
            )
        }
        if (raw.isEmpty()) {
            throw MisconfigurationException(
                "yaml-data value source: list under '$key' in '$path' is empty. " +
                "Provide at least one value."
            )
        }
        return raw
    }
}
```

- [ ] **Step 4: Run — 4 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/YamlBackedValueSourceTest.kt
git commit -m "feat(datagen): add YamlBackedValueSource with rich error messages"
```

---

### Task 14: NullRateApplicator

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicatorTest.kt`

Wraps any `ValueSource` and emits `null` at the declared rate using a seedable `Random`.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import java.util.Random
import kotlin.test.Test

class NullRateApplicatorTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `null rate of zero never produces null`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 0.0, random = Random(1L))
        repeat(1000) { assertThat(applicator.next(ctx)).isNotNull() }
    }

    @Test
    fun `null rate of one always produces null`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 1.0, random = Random(1L))
        repeat(100) { assertThat(applicator.next(ctx)).isNull() }
    }

    @Test
    fun `null rate of one half lands within statistical tolerance`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 0.5, random = Random(42L))
        val nullCount = (1..10_000).count { applicator.next(ctx) == null }
        assertThat(nullCount.toDouble() / 10_000).isBetween(0.46, 0.54)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicator.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import java.util.Random

class NullRateApplicator(
    private val inner: ValueSource,
    private val nullRate: Double,
    private val random: Random,
) : ValueSource {
    override fun next(ctx: GenerationContext): Any? =
        if (nullRate > 0.0 && random.nextDouble() < nullRate) null else inner.next(ctx)
}
```

- [ ] **Step 4: Run — 3 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicator.kt src/test/kotlin/com/gridgain/demo/datagen/generation/NullRateApplicatorTest.kt
git commit -m "feat(datagen): add NullRateApplicator wrapping any ValueSource"
```

---

### Task 15: ValueSourceFactory

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt`

Translates a `ValueSourceSpec` plus column metadata into a runtime `ValueSource`. Wraps the result in a `NullRateApplicator` when `null_rate > 0`. Resolves yaml-data paths relative to a configurable root.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class ValueSourceFactoryTest {

    private fun column(spec: com.gridgain.demo.datagen.config.ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = "c", nullRate = nullRate, valueSource = spec)

    @Test
    fun `builds a SequenceValueSource for SequenceSpec`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 5, step = 1)))
        assertThat(vs).isInstanceOf(SequenceValueSource::class.java)
    }

    @Test
    fun `wraps in NullRateApplicator when null_rate is positive`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 1, step = 1), nullRate = 0.1))
        assertThat(vs).isInstanceOf(NullRateApplicator::class.java)
    }

    @Test
    fun `does not wrap when null_rate is zero`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 1, step = 1), nullRate = 0.0))
        assertThat(vs).isNotInstanceOf(NullRateApplicator::class.java)
    }

    @Test
    fun `builds DataFaker, Unique, WeightedChoice, and Yaml-backed sources`(@TempDir dir: Path) {
        dir.resolve("c.yaml").writeText("xs:\n  - a\n  - b\n")
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        assertThat(factory.build(column(DataFakerSpec("#{name.firstName}"))))
            .isInstanceOf(DataFakerValueSource::class.java)
        assertThat(factory.build(column(UniqueSpec("#{name.firstName}"))))
            .isInstanceOf(UniqueValueSource::class.java)
        assertThat(factory.build(column(WeightedChoiceSpec(listOf(WeightedChoice("a", 1.0))))))
            .isInstanceOf(WeightedChoiceValueSource::class.java)
        assertThat(factory.build(column(YamlDataSpec(path = "c.yaml", key = "xs"))))
            .isInstanceOf(YamlBackedValueSource::class.java)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.ValueSourceSpec
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import java.nio.file.Path
import java.util.Random

class ValueSourceFactory(
    private val yamlDataRoot: Path,
    private val seed: Long,
    private val uniqueMaxRetries: Int = 1000,
) {

    fun build(column: ColumnSpec): ValueSource {
        val core = buildCore(column.valueSource)
        return if (column.nullRate > 0.0) {
            NullRateApplicator(core, column.nullRate, Random(seed + column.name.hashCode()))
        } else {
            core
        }
    }

    private fun buildCore(spec: ValueSourceSpec): ValueSource = when (spec) {
        is SequenceSpec -> SequenceValueSource(start = spec.start, step = spec.step)
        is DataFakerSpec -> DataFakerValueSource(spec.expression)
        is UniqueSpec -> UniqueValueSource(spec.expression, maxRetries = uniqueMaxRetries)
        is WeightedChoiceSpec -> WeightedChoiceValueSource(spec.choices, seed = seed)
        is YamlDataSpec -> YamlBackedValueSource(
            path = yamlDataRoot.resolve(spec.path),
            key = spec.key,
            random = Random(seed),
        )
    }
}
```

- [ ] **Step 4: Run — 4 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt
git commit -m "feat(datagen): add ValueSourceFactory translating spec to runtime"
```

---

### Task 16: RowGenerator

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/RowGeneratorTest.kt`

Per-schema row producer. Builds the column-name → ValueSource map from a `SchemaSpec` and emits a `LinkedHashMap<String, Any?>` per `next()` so column order is preserved.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/RowGeneratorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class RowGeneratorTest {

    @Test
    fun `produces a row with declared columns in declared order`(@TempDir dir: Path) {
        val schema = SchemaSpec(
            name = "customer",
            updateRatio = 0.0,
            columns = listOf(
                ColumnSpec("id", 0.0, SequenceSpec(start = 1, step = 1)),
                ColumnSpec("name", 0.0, DataFakerSpec("#{name.firstName}")),
            ),
        )
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = RowGenerator(schema, factory, faker = Faker())

        val row = gen.next()
        assertThat(row.keys.toList()).containsExactly("id", "name")
        assertThat(row["id"]).isEqualTo(1L)
        assertThat(row["name"]).isInstanceOf(String::class.java)

        assertThat(gen.next()["id"]).isEqualTo(2L)
    }

    @Test
    fun `null_rate of one yields null in the corresponding column`(@TempDir dir: Path) {
        val schema = SchemaSpec(
            name = "s",
            updateRatio = 0.0,
            columns = listOf(
                ColumnSpec("forced_null", 1.0, SequenceSpec(start = 1, step = 1)),
            ),
        )
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = RowGenerator(schema, factory, faker = Faker())
        repeat(20) { assertThat(gen.next()["forced_null"]).isNull() }
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.SchemaSpec
import net.datafaker.Faker

class RowGenerator(
    private val schema: SchemaSpec,
    factory: ValueSourceFactory,
    faker: Faker,
) {
    private val sources: List<Pair<String, ValueSource>> =
        schema.columns.map { it.name to factory.build(it) }
    private val ctx: GenerationContext = GenerationContext(faker)

    fun next(): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(sources.size)
        for ((name, source) in sources) {
            out[name] = source.next(ctx)
        }
        return out
    }
}
```

- [ ] **Step 4: Run — 2 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt src/test/kotlin/com/gridgain/demo/datagen/generation/RowGeneratorTest.kt
git commit -m "feat(datagen): add per-schema RowGenerator"
```

---

### Task 17: Full-suite green check

**Files:** none — verification only.

- [ ] **Step 1: Clean build**

`./gradlew clean test`. Expected: `BUILD SUCCESSFUL`.

Total tests after Plan 2 complete: 32 (Plan 1) + 32 (Plan 2 new tests across 12 new test classes) = **64**.

- [ ] **Step 2: Inspect**

`./gradlew clean build` — `BUILD SUCCESSFUL`.

---

## Verification (end-to-end smoke)

1. From the data-generator directory, parse `src/test/resources/data-v2-customer.yaml` via `ConfigurationParser`. Confirm `parsed.data.schemas.single().name == "customer"` and that all five value sources are present and correctly typed.
2. Build a `RowGenerator` for the `customer` schema using `ValueSourceFactory(yamlDataRoot = <repo>/data, seed = 0)`. Provide the yaml-backed list at the referenced path. Pull 100 rows and confirm:
   - all five column names appear in the same order each row,
   - `id` strictly increases by 1 starting at 1,
   - `state` only ever takes values `CA | NY | TX`,
   - `handle` values are unique across all 100 rows,
   - `first_name` is populated 98%+ of the time (null_rate=0.02),
   - `zip` is drawn from the yaml file's `zip_codes` list.
3. Manually corrupt the v2 yaml (e.g., remove `update_ratio` from a schema) and re-run; confirm `MisconfigurationException` with a message naming `data.yaml`, `schema_version 2`, and the missing field.
4. Manually duplicate a column name in the v2 yaml; confirm the `ColumnUniquenessValidator` error appears with both the column name and the schema name in the message.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §1 schema = ordered columns; column = value-source binding | Tasks 3, 4 |
| §1 built-in providers: `sequence`, `unique`, `weighted-choice`, `yaml-data`, plus stock DataFaker | Tasks 9–13 |
| §1 `null_rate` on simple columns | Task 14 + factory wiring (Task 15) |
| §1 per-schema `update_ratio` field present in config | Task 4 (field exists; execution deferred to Plan 3/4) |
| §6 schema versioning + migration runner: data v2 added with v1→v2 path | Tasks 1, 5 |
| §6 JSONSchema validation, cross-element validation | Tasks 2, 7 |
| Project rule: rich error messages | Tasks 5, 7, 10–13 |
| Project rule: no defaults on template classes | Tasks 3, 4 — every field required, no `= default` on data class properties |
| Project rule: no nullable types without explicit approval | `Any?` is used only on `ValueSource.next()` return because `null_rate` semantically requires it; documented in Task 8's KDoc |

**Out of scope for this plan (deferred to Plan 3 or beyond):**
- `parent-fk-ref` value source (depends on cross-row state).
- `key-suffix` value source (depends on cross-column reference within a row).
- Cohort sampler and cohort buckets (Plan 3).
- `update_ratio` execution: actually picking already-emitted keys (Plan 3 or 4 — the field is already in the typed model).
- Affinity column annotation (Plan 5 — provisioning).
- Scenario engine (Plan 4).
- Transaction wrapping, KV writes (Plan 5).

---

## Critical files (forward references for Plan 3+)

- `generation/ValueSource.kt` — the stable contract Plan 3 will extend `GenerationContext` against.
- `config/DataConfig.kt` — Plan 3 will add `relations` and `cohort_buckets` fields, bumping `CURRENT_DATA_SCHEMA_VERSION` to 3 with a `MigrateV2toV3`.
- `config/CrossElementValidator.kt` — Plan 3 adds `RelationReferentialValidator` and `NullRateOnRelationColumnValidator` and composes them.
- `generation/ValueSourceFactory.kt` — Plan 3 adds `ParentFkRefValueSource` and `KeySuffixValueSource` to the `when` branch.
