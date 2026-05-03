# Data Generator — Plan 3: Cohorts, Relations & Business Events

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the two cross-row value-source kinds (`parent-fk-ref`, `key-suffix`), the cohort sampler that distributes children-per-parent following user-declared bucket shares, the GenerationContext extension that lets value sources see prior columns and parent rows, and a `BusinessEventGenerator` that emits a parent row plus its cohort-determined children. End deliverable: given a Customer schema and an Order schema with `parent-fk-ref` to Customer and cohort buckets, the generator emits a stream of business events whose row counts match the declared bucket distribution within statistical tolerance.

**Architecture:** Extend Plan 2's sealed `ValueSourceSpec` with `ParentFkRefSpec` and `KeySuffixSpec` — these are additive in v2 (no schema-version bump, no new migration). Refactor `GenerationContext` to include a mutable `rowSoFar` (cross-column ref) and an immutable `parentRow` (cross-row ref). `RowGenerator.next()` constructs a fresh context per call and threads it through column generation. `CohortSampler` is pure logic: given a parent count and a list of buckets, deterministically (seedable) assign each parent to a bucket and report `Map<parentId, childCount>`. `BusinessEventGenerator` ties a parent schema to one or more child schemas via the parent-fk-ref column declaration; on each `next()` it emits one parent row plus the cohort-determined children, returning a `BusinessEvent` (parent row + per-child-schema list of rows).

**Tech Stack:** Same as Plan 2. No new dependencies.

---

## Spec extension to v2 yaml

```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.05
    columns:
      - name: id
        null_rate: 0.0
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: name
        null_rate: 0.0
        value_source: { kind: datafaker, expression: "#{name.fullName}" }
  - name: order
    update_ratio: 0.0
    columns:
      - name: id
        null_rate: 0.0
        value_source:
          kind: key-suffix
          base_column: customer_id
          separator: "-"
          length: 6
      - name: customer_id
        null_rate: 0.0
        value_source:
          kind: parent-fk-ref
          parent_schema: customer
          parent_column: id
          cohort_buckets:
            - { share: 0.05, multiplier: 100 }
            - { share: 0.45, multiplier: 10 }
            - { share: 0.50, multiplier: 1 }
      - name: amount
        null_rate: 0.02
        value_source: { kind: datafaker, expression: "#{number.numberBetween '1' '1000'}" }
```

A schema's role as parent or child is implicit — a schema is a child if any of its columns has a `parent-fk-ref` value source. Per spec brainstorm Q4, cohort buckets attach to the FK column.

All shares in `cohort_buckets` must sum to ~1.0 (within rounding); the cross-element validator enforces this.

---

## File Structure

```
gridgain-demo-data-generator/
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── ValueSourceSpec.kt           # add ParentFkRefSpec, KeySuffixSpec, CohortBucket
│   │   └── CrossElementValidator.kt     # add RelationReferentialValidator,
│   │                                    # NullRateOnRelationColumnValidator,
│   │                                    # CohortBucketSharesValidator
│   ├── generation/
│   │   ├── ValueSource.kt               # extend GenerationContext
│   │   ├── RowGenerator.kt              # refactor for per-call ctx
│   │   ├── ValueSourceFactory.kt        # add new spec branches
│   │   ├── ParentFkRefValueSource.kt    # NEW
│   │   ├── KeySuffixValueSource.kt      # NEW
│   │   ├── CohortSampler.kt             # NEW
│   │   └── BusinessEventGenerator.kt    # NEW (orchestrator)
├── src/main/resources/schema/
│   └── data/v2.schema.json              # extend with parent-fk-ref and key-suffix
└── src/test/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   ├── RelationReferentialValidatorTest.kt
    │   ├── NullRateOnRelationColumnValidatorTest.kt
    │   ├── CohortBucketSharesValidatorTest.kt
    │   └── DataConfigDeserializationTest.kt   # extend with the two new kinds
    └── generation/
        ├── KeySuffixValueSourceTest.kt
        ├── ParentFkRefValueSourceTest.kt
        ├── CohortSamplerTest.kt
        ├── RowGeneratorTest.kt          # extend for per-call ctx + key-suffix integration
        ├── ValueSourceFactoryTest.kt    # extend for new kinds
        └── BusinessEventGeneratorTest.kt
```

---

### Task 1: Extend v2 JSONSchema with the two new value-source kinds

**Files:**
- Modify: `src/main/resources/schema/data/v2.schema.json` — add `vs_parent_fk_ref` and `vs_key_suffix` definitions and add their `$ref`s to the `value_source.oneOf` list.

- [ ] **Step 1: Update the schema**

Apply this minimal patch to `src/main/resources/schema/data/v2.schema.json` — leave every other key intact:

In `$defs.value_source.oneOf`, append two entries:

```json
{ "$ref": "#/$defs/vs_parent_fk_ref" },
{ "$ref": "#/$defs/vs_key_suffix" }
```

In `$defs`, add:

```json
"vs_parent_fk_ref": {
  "type": "object",
  "required": ["kind", "parent_schema", "parent_column", "cohort_buckets"],
  "additionalProperties": false,
  "properties": {
    "kind": { "const": "parent-fk-ref" },
    "parent_schema": { "type": "string", "minLength": 1 },
    "parent_column": { "type": "string", "minLength": 1 },
    "cohort_buckets": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["share", "multiplier"],
        "additionalProperties": false,
        "properties": {
          "share": { "type": "number", "exclusiveMinimum": 0.0, "maximum": 1.0 },
          "multiplier": { "type": "integer", "minimum": 0 }
        }
      }
    }
  }
},
"vs_key_suffix": {
  "type": "object",
  "required": ["kind", "base_column", "separator", "length"],
  "additionalProperties": false,
  "properties": {
    "kind": { "const": "key-suffix" },
    "base_column": { "type": "string", "minLength": 1 },
    "separator": { "type": "string" },
    "length": { "type": "integer", "minimum": 1, "maximum": 32 }
  }
}
```

- [ ] **Step 2: Verify resources package**

`./gradlew processResources` — `BUILD SUCCESSFUL`. Confirm the schema is at `build/resources/main/schema/data/v2.schema.json`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/schema/data/v2.schema.json
git commit -m "feat(datagen): extend v2 JSONSchema with parent-fk-ref and key-suffix"
```

Sign with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

### Task 2: Extend ValueSourceSpec hierarchy

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt`

Add two new spec types and a `CohortBucket` data class. Register both with Jackson polymorphism.

- [ ] **Step 1: Modify the file**

In `src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt`, add the two new entries inside `@JsonSubTypes`:

```kotlin
JsonSubTypes.Type(value = ParentFkRefSpec::class, name = "parent-fk-ref"),
JsonSubTypes.Type(value = KeySuffixSpec::class, name = "key-suffix"),
```

Append the data classes at the bottom of the file:

```kotlin
data class CohortBucket(val share: Double, val multiplier: Int)

data class ParentFkRefSpec(
    @com.fasterxml.jackson.annotation.JsonProperty("parent_schema") val parentSchema: String,
    @com.fasterxml.jackson.annotation.JsonProperty("parent_column") val parentColumn: String,
    @com.fasterxml.jackson.annotation.JsonProperty("cohort_buckets") val cohortBuckets: List<CohortBucket>,
) : ValueSourceSpec()

data class KeySuffixSpec(
    @com.fasterxml.jackson.annotation.JsonProperty("base_column") val baseColumn: String,
    val separator: String,
    val length: Int,
) : ValueSourceSpec()
```

- [ ] **Step 2: Verify compile**

`./gradlew compileKotlin`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ValueSourceSpec.kt
git commit -m "feat(datagen): add ParentFkRefSpec and KeySuffixSpec with CohortBucket"
```

---

### Task 3: Extend deserialization test for new kinds

**Files:**
- Modify: `src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt`
- Create: `src/test/resources/data-v2-customer-order.yaml`

Adds a fixture covering Customer + Order with `parent-fk-ref` + `key-suffix`, and an end-to-end deserialization test confirming both new kinds populate correctly.

- [ ] **Step 1: Add fixture**

Create `src/test/resources/data-v2-customer-order.yaml`:

```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.05
    columns:
      - name: id
        null_rate: 0.0
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: name
        null_rate: 0.0
        value_source: { kind: datafaker, expression: "#{name.fullName}" }
  - name: order
    update_ratio: 0.0
    columns:
      - name: customer_id
        null_rate: 0.0
        value_source:
          kind: parent-fk-ref
          parent_schema: customer
          parent_column: id
          cohort_buckets:
            - { share: 0.10, multiplier: 100 }
            - { share: 0.40, multiplier: 10 }
            - { share: 0.50, multiplier: 1 }
      - name: id
        null_rate: 0.0
        value_source:
          kind: key-suffix
          base_column: customer_id
          separator: "-"
          length: 6
```

- [ ] **Step 2: Append to existing test class**

In `src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt`, append a new test:

```kotlin
@Test
fun `deserializes parent-fk-ref and key-suffix kinds`(@TempDir dir: Path) {
    val data = copy(dir, "data-v2-customer-order.yaml", "data.yaml")
    val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
    val parsed = ConfigurationParser(logger = logger).parse(data.toFile(), ops.toFile())

    val order = parsed.data.schemas.first { it.name == "order" }
    val fkColumn = order.columns.first { it.name == "customer_id" }
    val fk = fkColumn.valueSource as ParentFkRefSpec
    assertThat(fk.parentSchema).isEqualTo("customer")
    assertThat(fk.parentColumn).isEqualTo("id")
    assertThat(fk.cohortBuckets).containsExactly(
        CohortBucket(share = 0.10, multiplier = 100),
        CohortBucket(share = 0.40, multiplier = 10),
        CohortBucket(share = 0.50, multiplier = 1),
    )

    val idColumn = order.columns.first { it.name == "id" }
    val ks = idColumn.valueSource as KeySuffixSpec
    assertThat(ks.baseColumn).isEqualTo("customer_id")
    assertThat(ks.separator).isEqualTo("-")
    assertThat(ks.length).isEqualTo(6)
}
```

- [ ] **Step 3: Run — expect PASS**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.DataConfigDeserializationTest'`

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/gridgain/demo/datagen/config/DataConfigDeserializationTest.kt src/test/resources/data-v2-customer-order.yaml
git commit -m "test(datagen): deserialization for parent-fk-ref and key-suffix kinds"
```

---

### Task 4: Refactor GenerationContext (rowSoFar + parentRow)

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt`

Add `rowSoFar` (mutable map populated as `RowGenerator` walks columns) and `parentRow` (immutable map provided when generating a child row). Existing tests construct `GenerationContext(faker)` — that must keep compiling.

- [ ] **Step 1: Modify the file**

Replace `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import net.datafaker.Faker

interface ValueSource {
    fun next(ctx: GenerationContext): Any?
}

/**
 * Per-row context. `rowSoFar` is mutable — RowGenerator populates it as it walks
 * columns left-to-right so later columns can reference earlier ones (key-suffix).
 * `parentRow` is the parent row when this row is being generated as a child of
 * another schema (parent-fk-ref); null otherwise.
 */
data class GenerationContext(
    val faker: Faker,
    val rowSoFar: MutableMap<String, Any?> = mutableMapOf(),
    val parentRow: Map<String, Any?>? = null,
)
```

The default values keep `GenerationContext(faker)` callable, so all Plan 2 tests still compile.

- [ ] **Step 2: Verify compile + full suite green**

`./gradlew test`. All 64 prior tests must still pass; no new tests in this task.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSource.kt
git commit -m "feat(datagen): extend GenerationContext with rowSoFar and parentRow"
```

---

### Task 5: Refactor RowGenerator for per-call context

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt`

`RowGenerator.next()` now constructs a fresh `GenerationContext` each call, populates `rowSoFar` as it walks columns, and accepts an optional `parentRow` parameter for child-row generation. Existing tests need their assertions on `next()` to keep working unchanged.

- [ ] **Step 1: Modify the file**

`src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.SchemaSpec
import net.datafaker.Faker

class RowGenerator(
    private val schema: SchemaSpec,
    factory: ValueSourceFactory,
    private val faker: Faker,
) {
    private val sources: List<Pair<String, ValueSource>> =
        schema.columns.map { it.name to factory.build(it) }

    fun next(parentRow: Map<String, Any?>? = null): LinkedHashMap<String, Any?> {
        val rowSoFar: MutableMap<String, Any?> = mutableMapOf()
        val ctx = GenerationContext(faker = faker, rowSoFar = rowSoFar, parentRow = parentRow)
        val out = LinkedHashMap<String, Any?>(sources.size)
        for ((name, source) in sources) {
            val value = source.next(ctx)
            rowSoFar[name] = value
            out[name] = value
        }
        return out
    }
}
```

The existing zero-argument `next()` call still works because `parentRow` defaults to `null`.

- [ ] **Step 2: Verify suite green**

`./gradlew test` — all 64 prior tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt
git commit -m "feat(datagen): RowGenerator constructs per-call ctx with rowSoFar"
```

---

### Task 6: KeySuffixValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSourceTest.kt`

Reads `ctx.rowSoFar[baseColumn]`, appends `separator` and a random alphanumeric suffix of declared length. Throws `MisconfigurationException` if `baseColumn` is not yet in `rowSoFar` (must be declared earlier in the column list).

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Random
import kotlin.test.Test

class KeySuffixValueSourceTest {

    @Test
    fun `appends separator and random suffix to base column value`() {
        val ctx = GenerationContext(
            faker = Faker(),
            rowSoFar = mutableMapOf("customer_id" to "C123"),
        )
        val s = KeySuffixValueSource(baseColumn = "customer_id", separator = "-", length = 6, random = Random(1L))
        val v = s.next(ctx) as String
        assertThat(v).startsWith("C123-")
        assertThat(v.substringAfter("-")).hasSize(6).matches("^[a-z0-9]+$")
    }

    @Test
    fun `produces distinct suffixes across calls`() {
        val ctx = GenerationContext(
            faker = Faker(),
            rowSoFar = mutableMapOf("base" to "X"),
        )
        val s = KeySuffixValueSource(baseColumn = "base", separator = "-", length = 8, random = Random(42L))
        val seen = (1..50).map { s.next(ctx) as String }.toSet()
        assertThat(seen.size).isEqualTo(50)
    }

    @Test
    fun `missing base column produces remediation`() {
        val ctx = GenerationContext(faker = Faker())
        val s = KeySuffixValueSource(baseColumn = "absent", separator = "-", length = 6, random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("absent")
            .hasMessageContaining("declared earlier")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class KeySuffixValueSource(
    private val baseColumn: String,
    private val separator: String,
    private val length: Int,
    private val random: Random,
) : ValueSource {

    private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

    override fun next(ctx: GenerationContext): Any {
        val base = ctx.rowSoFar[baseColumn] ?: throw MisconfigurationException(
            "key-suffix value source references base_column '$baseColumn' " +
            "but no such column has been generated yet for this row. " +
            "Move '$baseColumn' to be declared earlier in the column list."
        )
        val suffix = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "$base$separator$suffix"
    }
}
```

- [ ] **Step 4: Run — 3 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/KeySuffixValueSourceTest.kt
git commit -m "feat(datagen): add KeySuffixValueSource with rowSoFar lookup"
```

---

### Task 7: ParentFkRefValueSource

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSource.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSourceTest.kt`

Reads `ctx.parentRow[parentColumn]`. Throws if `ctx.parentRow` is null or lacks the column. Cohort buckets are NOT consulted by this value source — they're consumed by the cohort sampler at the orchestrator level (Task 9).

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSourceTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ParentFkRefValueSourceTest {

    @Test
    fun `returns the parent column value`() {
        val ctx = GenerationContext(
            faker = Faker(),
            parentRow = mapOf("id" to 42L, "name" to "Alice"),
        )
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThat(s.next(ctx)).isEqualTo(42L)
    }

    @Test
    fun `null parentRow is rejected with remediation`() {
        val ctx = GenerationContext(faker = Faker())
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("customer")
            .hasMessageContaining("parent row")
    }

    @Test
    fun `parentRow missing the named column is rejected`() {
        val ctx = GenerationContext(
            faker = Faker(),
            parentRow = mapOf("name" to "Alice"),
        )
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("customer.id")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSource.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class ParentFkRefValueSource(
    private val parentSchema: String,
    private val parentColumn: String,
) : ValueSource {

    override fun next(ctx: GenerationContext): Any? {
        val parent = ctx.parentRow ?: throw MisconfigurationException(
            "parent-fk-ref to '$parentSchema.$parentColumn' requires a parent row, " +
            "but no parent row was supplied. " +
            "Generate child rows via BusinessEventGenerator, not RowGenerator.next() directly."
        )
        if (!parent.containsKey(parentColumn)) {
            throw MisconfigurationException(
                "parent-fk-ref references '$parentSchema.$parentColumn' " +
                "but the supplied parent row has no such column. " +
                "Verify the parent_column matches a column declared in the '$parentSchema' schema."
            )
        }
        return parent[parentColumn]
    }
}
```

- [ ] **Step 4: Run — 3 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSource.kt src/test/kotlin/com/gridgain/demo/datagen/generation/ParentFkRefValueSourceTest.kt
git commit -m "feat(datagen): add ParentFkRefValueSource reading from parentRow"
```

---

### Task 8: CohortSampler

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/CohortSampler.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/CohortSamplerTest.kt`

Pure logic: assign each parent (identified by index 0..n-1) to a bucket according to bucket shares, deterministic given a seed. Returns `IntArray` where `result[parentIndex] = childCountForThatParent`. No persistence — the caller decides what to do with the assignment.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/CohortSamplerTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class CohortSamplerTest {

    @Test
    fun `assigns counts that match bucket shares within tolerance`() {
        val buckets = listOf(
            CohortBucket(share = 0.10, multiplier = 100),
            CohortBucket(share = 0.40, multiplier = 10),
            CohortBucket(share = 0.50, multiplier = 1),
        )
        val counts = CohortSampler(seed = 42L).assign(parentCount = 1000, buckets = buckets)
        val whales = counts.count { it == 100 }
        val mids = counts.count { it == 10 }
        val guppies = counts.count { it == 1 }
        assertThat(whales + mids + guppies).isEqualTo(1000)
        assertThat(whales).isBetween(80, 120)
        assertThat(mids).isBetween(370, 430)
        assertThat(guppies).isBetween(470, 530)
    }

    @Test
    fun `multiplier of zero produces zero children for that bucket`() {
        val counts = CohortSampler(seed = 1L)
            .assign(parentCount = 100, buckets = listOf(CohortBucket(share = 1.0, multiplier = 0)))
        assertThat(counts.toSet()).containsExactly(0)
    }

    @Test
    fun `single bucket assigns all parents to it`() {
        val counts = CohortSampler(seed = 1L)
            .assign(parentCount = 50, buckets = listOf(CohortBucket(share = 1.0, multiplier = 7)))
        assertThat(counts.toSet()).containsExactly(7)
        assertThat(counts.size).isEqualTo(50)
    }

    @Test
    fun `bucket shares not summing to one are rejected`() {
        assertThatThrownBy {
            CohortSampler(seed = 1L).assign(
                parentCount = 10,
                buckets = listOf(
                    CohortBucket(share = 0.30, multiplier = 1),
                    CohortBucket(share = 0.30, multiplier = 5),
                ),
            )
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("0.6")
    }

    @Test
    fun `same seed produces identical assignment`() {
        val buckets = listOf(
            CohortBucket(share = 0.5, multiplier = 10),
            CohortBucket(share = 0.5, multiplier = 1),
        )
        val a = CohortSampler(seed = 99L).assign(parentCount = 50, buckets = buckets)
        val b = CohortSampler(seed = 99L).assign(parentCount = 50, buckets = buckets)
        assertThat(a).isEqualTo(b)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/generation/CohortSampler.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class CohortSampler(seed: Long) {

    private val random: Random = Random(seed)

    fun assign(parentCount: Int, buckets: List<CohortBucket>): IntArray {
        val totalShare = buckets.sumOf { it.share }
        if (kotlin.math.abs(totalShare - 1.0) > 0.001) {
            throw MisconfigurationException(
                "cohort_buckets shares must sum to 1.0 (within 0.001 tolerance); " +
                "got ${"%.3f".format(totalShare)}. " +
                "Adjust the share values so they total 1.0."
            )
        }
        val cumulative: List<Pair<Double, Int>> =
            buckets.runningFold(0.0 to 0) { acc, b -> (acc.first + b.share) to b.multiplier }
                .drop(1)
        val out = IntArray(parentCount)
        for (i in 0 until parentCount) {
            val r = random.nextDouble()
            out[i] = cumulative.first { r < it.first || it === cumulative.last() }.second
        }
        return out
    }
}
```

- [ ] **Step 4: Run — 5 tests PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/CohortSampler.kt src/test/kotlin/com/gridgain/demo/datagen/generation/CohortSamplerTest.kt
git commit -m "feat(datagen): add CohortSampler with seedable bucket assignment"
```

---

### Task 9: ValueSourceFactory updates

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt`
- Modify: `src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt`

Add the two new spec branches to the factory's `when`. The `KeySuffixValueSource` needs a `Random`; derive it from `seed + column.name.hashCode()` so columns are decorrelated.

- [ ] **Step 1: Update the impl**

In `src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt`, add imports and two new `when` branches in `buildCore`:

```kotlin
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import java.util.Random
```

```kotlin
is ParentFkRefSpec -> ParentFkRefValueSource(
    parentSchema = spec.parentSchema,
    parentColumn = spec.parentColumn,
)
is KeySuffixSpec -> KeySuffixValueSource(
    baseColumn = spec.baseColumn,
    separator = spec.separator,
    length = spec.length,
    random = Random(seed + spec.baseColumn.hashCode()),
)
```

(Place these before the trailing `}` of the `when` over `ValueSourceSpec`.)

- [ ] **Step 2: Add factory tests**

Append to `src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt`:

```kotlin
@Test
fun `builds ParentFkRef and KeySuffix sources`(@TempDir dir: Path) {
    val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
    assertThat(
        factory.build(column(ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1)))))
    ).isInstanceOf(ParentFkRefValueSource::class.java)
    assertThat(
        factory.build(column(KeySuffixSpec(baseColumn = "id", separator = "-", length = 4)))
    ).isInstanceOf(KeySuffixValueSource::class.java)
}
```

Add the matching imports to the test file:

```kotlin
import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
```

- [ ] **Step 3: Run — full suite green**

`./gradlew test`. Expected: all prior tests still pass, plus the new factory test.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt
git commit -m "feat(datagen): wire ParentFkRef and KeySuffix into ValueSourceFactory"
```

---

### Task 10: Cross-element validators (relations + null_rate + cohort shares)

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/RelationReferentialValidatorTest.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/NullRateOnRelationColumnValidatorTest.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/CohortBucketSharesValidatorTest.kt`

Three concrete cross-element rules. Composed into `CompositeCrossElementValidator` alongside `DefaultCrossElementValidator` and `ColumnUniquenessValidator`.

- [ ] **Step 1: Write the three tests**

`src/test/kotlin/com/gridgain/demo/datagen/config/RelationReferentialValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class RelationReferentialValidatorTest {

    private fun col(name: String, vs: ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = name, nullRate = nullRate, valueSource = vs)

    private val customer = SchemaSpec(
        name = "customer", updateRatio = 0.0,
        columns = listOf(col("id", SequenceSpec(1, 1)))
    )

    @Test
    fun `accepts a relation that resolves`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        assertThat(RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1)).errors).isEmpty()
    }

    @Test
    fun `rejects unknown parent schema`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("missing", "id", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("order.customer_id").contains("missing")
    }

    @Test
    fun `rejects unknown parent column`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("customer", "absent", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer.absent")
    }
}
```

`src/test/kotlin/com/gridgain/demo/datagen/config/NullRateOnRelationColumnValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class NullRateOnRelationColumnValidatorTest {

    private fun col(name: String, vs: ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = name, nullRate = nullRate, valueSource = vs)

    @Test
    fun `accepts null_rate zero on a parent-fk-ref column`() {
        val s = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("c", "id", listOf(CohortBucket(1.0, 1))), nullRate = 0.0))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(s))
        assertThat(NullRateOnRelationColumnValidator().validate(data, OpsConfig(schemaVersion = 1)).errors).isEmpty()
    }

    @Test
    fun `rejects positive null_rate on a parent-fk-ref column`() {
        val s = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("c", "id", listOf(CohortBucket(1.0, 1))), nullRate = 0.05))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(s))
        val r = NullRateOnRelationColumnValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("order.customer_id").contains("null_rate")
    }
}
```

`src/test/kotlin/com/gridgain/demo/datagen/config/CohortBucketSharesValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class CohortBucketSharesValidatorTest {

    private fun col(vs: ValueSourceSpec) = ColumnSpec(name = "fk", nullRate = 0.0, valueSource = vs)

    @Test
    fun `accepts buckets summing to 1`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(SchemaSpec("o", 0.0, listOf(col(
                ParentFkRefSpec("c", "id", listOf(CohortBucket(0.7, 1), CohortBucket(0.3, 5)))
            ))))
        )
        assertThat(CohortBucketSharesValidator().validate(data, OpsConfig(schemaVersion = 1)).errors).isEmpty()
    }

    @Test
    fun `rejects buckets that do not sum to 1`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(SchemaSpec("o", 0.0, listOf(col(
                ParentFkRefSpec("c", "id", listOf(CohortBucket(0.3, 1), CohortBucket(0.3, 5)))
            ))))
        )
        val r = CohortBucketSharesValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("o.fk").contains("0.6")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement the three validators**

Append to `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`:

```kotlin
class RelationReferentialValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val schemasByName = data.schemas.associateBy { it.name }
        for (schema in data.schemas) {
            for (column in schema.columns) {
                val vs = column.valueSource
                if (vs is ParentFkRefSpec) {
                    val parent = schemasByName[vs.parentSchema]
                    if (parent == null) {
                        errors += "${schema.name}.${column.name}: parent schema '${vs.parentSchema}' " +
                            "is not declared in data.yaml. Add the schema or correct the parent_schema reference."
                    } else if (parent.columns.none { it.name == vs.parentColumn }) {
                        errors += "${schema.name}.${column.name}: parent column " +
                            "'${vs.parentSchema}.${vs.parentColumn}' is not declared on the parent schema. " +
                            "Verify the parent_column reference."
                    }
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class NullRateOnRelationColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            for (column in schema.columns) {
                if (column.valueSource is ParentFkRefSpec && column.nullRate > 0.0) {
                    errors += "${schema.name}.${column.name}: null_rate is not valid on relation columns; " +
                        "relation columns are populated from their parent and cannot be null."
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class CohortBucketSharesValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            for (column in schema.columns) {
                val vs = column.valueSource
                if (vs is ParentFkRefSpec) {
                    val total = vs.cohortBuckets.sumOf { it.share }
                    if (kotlin.math.abs(total - 1.0) > 0.001) {
                        errors += "${schema.name}.${column.name}: cohort_buckets shares sum to " +
                            "${"%.3f".format(total)} but must sum to 1.0 (within 0.001 tolerance). " +
                            "Adjust the share values so they total 1.0."
                    }
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
```

- [ ] **Step 4: Compose into the parser default**

In `ConfigurationParser.kt`, update the cross-element validator default:

```kotlin
private val crossElementValidator: CrossElementValidator = CompositeCrossElementValidator(
    listOf(
        DefaultCrossElementValidator(),
        ColumnUniquenessValidator(),
        RelationReferentialValidator(),
        NullRateOnRelationColumnValidator(),
        CohortBucketSharesValidator(),
    )
),
```

- [ ] **Step 5: Run all three test classes — PASS.**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.RelationReferentialValidatorTest' --tests 'com.gridgain.demo.datagen.config.NullRateOnRelationColumnValidatorTest' --tests 'com.gridgain.demo.datagen.config.CohortBucketSharesValidatorTest'`

- [ ] **Step 6: Run full suite — green.**

`./gradlew test`

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt src/test/kotlin/com/gridgain/demo/datagen/config/RelationReferentialValidatorTest.kt src/test/kotlin/com/gridgain/demo/datagen/config/NullRateOnRelationColumnValidatorTest.kt src/test/kotlin/com/gridgain/demo/datagen/config/CohortBucketSharesValidatorTest.kt
git commit -m "feat(datagen): add three cross-element validators for relations and cohorts"
```

---

### Task 11: BusinessEventGenerator

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGenerator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGeneratorTest.kt`

Top-level orchestrator. Constructor takes the `DataConfig`, the root schema name, the factory, and the faker. On each `next()`:
1. Generate one root row via the root schema's `RowGenerator`.
2. For each child schema (any schema with a `parent-fk-ref` column whose `parent_schema` equals this root schema's name), use the cohort sampler to determine how many child rows to emit for this single parent.
3. Generate that many child rows, threading the root row in as `parentRow`.

Returns `BusinessEvent(parentRow, childrenBySchema: Map<String, List<Row>>)`.

The sampler runs **once per `next()` call** with `parentCount = 1`, so the assigned multiplier for that one parent comes from the cohort distribution. For statistical validity over many calls, the sampler is constructed once and reused.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGeneratorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class BusinessEventGeneratorTest {

    private fun col(name: String, vs: com.gridgain.demo.datagen.config.ValueSourceSpec) =
        ColumnSpec(name = name, nullRate = 0.0, valueSource = vs)

    @Test
    fun `emits a parent row with cohort-distributed children`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1)),
            col("name", DataFakerSpec("#{name.firstName}")),
        ))
        val order = SchemaSpec("order", 0.0, listOf(
            col("customer_id", ParentFkRefSpec("customer", "id", listOf(
                CohortBucket(share = 0.10, multiplier = 100),
                CohortBucket(share = 0.40, multiplier = 10),
                CohortBucket(share = 0.50, multiplier = 1),
            ))),
            col("amount", DataFakerSpec("#{number.numberBetween '1' '1000'}")),
        ))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 42L)
        val gen = BusinessEventGenerator(
            data = data,
            rootSchemaName = "customer",
            factory = factory,
            faker = Faker(),
            cohortSeed = 42L,
        )

        var totalOrders = 0
        var whales = 0
        var mids = 0
        var guppies = 0
        repeat(1000) {
            val event = gen.next()
            assertThat(event.parentRow["id"]).isInstanceOf(Long::class.javaObjectType)
            val orders = event.childrenBySchema["order"]!!
            totalOrders += orders.size
            when (orders.size) {
                100 -> whales++
                10 -> mids++
                1 -> guppies++
            }
            // Each child carries the same parent id back-referenced.
            for (orow in orders) {
                assertThat(orow["customer_id"]).isEqualTo(event.parentRow["id"])
            }
        }
        assertThat(whales).isBetween(80, 120)
        assertThat(mids).isBetween(370, 430)
        assertThat(guppies).isBetween(470, 530)
        // Total sanity: ~ (whales*100 + mids*10 + guppies*1)
        assertThat(totalOrders).isBetween(13_000, 17_000)
    }

    @Test
    fun `event for a leaf schema produces no children`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1)),
        ))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer))
        val gen = BusinessEventGenerator(
            data = data,
            rootSchemaName = "customer",
            factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L),
            faker = Faker(),
            cohortSeed = 1L,
        )
        val event = gen.next()
        assertThat(event.childrenBySchema).isEmpty()
        assertThat(event.parentRow["id"]).isEqualTo(1L)
    }

    @Test
    fun `unknown root schema is rejected`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(col("id", SequenceSpec(1, 1))))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer))
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BusinessEventGenerator(
                data = data,
                rootSchemaName = "unknown",
                factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L),
                faker = Faker(),
                cohortSeed = 1L,
            )
        }
            .isInstanceOf(com.gridgain.demo.datagen.errors.MisconfigurationException::class.java)
            .hasMessageContaining("unknown")
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGenerator.kt`:

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker

data class BusinessEvent(
    val parentRow: LinkedHashMap<String, Any?>,
    val childrenBySchema: Map<String, List<LinkedHashMap<String, Any?>>>,
)

class BusinessEventGenerator(
    data: DataConfig,
    rootSchemaName: String,
    factory: ValueSourceFactory,
    faker: Faker,
    cohortSeed: Long,
) {

    private val rootSchema: SchemaSpec = data.schemas.firstOrNull { it.name == rootSchemaName }
        ?: throw MisconfigurationException(
            "BusinessEventGenerator: rootSchemaName '$rootSchemaName' is not declared in data.yaml. " +
            "Available schemas: ${data.schemas.joinToString(", ") { it.name }}."
        )

    private val rootGenerator: RowGenerator = RowGenerator(rootSchema, factory, faker)

    /**
     * For each child schema with a parent-fk-ref to the root, store its RowGenerator and the
     * cohort buckets to consult when deciding how many child rows to emit per parent.
     */
    private val childPlans: List<ChildPlan> = data.schemas
        .mapNotNull { childSchema ->
            val fkColumn = childSchema.columns.firstOrNull { col ->
                val vs = col.valueSource
                vs is ParentFkRefSpec && vs.parentSchema == rootSchemaName
            } ?: return@mapNotNull null
            val fkSpec = fkColumn.valueSource as ParentFkRefSpec
            ChildPlan(
                schemaName = childSchema.name,
                generator = RowGenerator(childSchema, factory, faker),
                buckets = fkSpec.cohortBuckets,
            )
        }

    private val sampler: CohortSampler = CohortSampler(seed = cohortSeed)

    fun next(): BusinessEvent {
        val parentRow = rootGenerator.next()
        val children: Map<String, List<LinkedHashMap<String, Any?>>> = childPlans.associate { plan ->
            val counts = sampler.assign(parentCount = 1, buckets = plan.buckets)
            val childCount = counts[0]
            plan.schemaName to (0 until childCount).map { plan.generator.next(parentRow = parentRow) }
        }
        return BusinessEvent(parentRow = parentRow, childrenBySchema = children)
    }

    private data class ChildPlan(
        val schemaName: String,
        val generator: RowGenerator,
        val buckets: List<com.gridgain.demo.datagen.config.CohortBucket>,
    )
}
```

- [ ] **Step 4: Run — 3 tests PASS.**

The statistical test in Step 1 calls `gen.next()` 1000 times, accumulating 1000 cohort assignments through the shared sampler — the distribution converges over those 1000 draws.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGenerator.kt src/test/kotlin/com/gridgain/demo/datagen/generation/BusinessEventGeneratorTest.kt
git commit -m "feat(datagen): add BusinessEventGenerator orchestrating parent and child rows"
```

---

### Task 12: Full-suite green check

**Files:** none — verification only.

- [ ] **Step 1: Clean build**

`./gradlew clean test` — `BUILD SUCCESSFUL`. Total tests after Plan 3 = 64 + 23 (1 deserialization extension + 3 KeySuffix + 3 ParentFkRef + 5 CohortSampler + 3 RelationReferential + 2 NullRateOnRelation + 2 CohortBucketShares + 1 ValueSourceFactory extension + 3 BusinessEvent) = **87**.

- [ ] **Step 2: Build**

`./gradlew clean build` — `BUILD SUCCESSFUL`.

---

## Verification (end-to-end smoke)

1. From the data-generator directory, parse the new test fixture `src/test/resources/data-v2-customer-order.yaml`. Confirm cross-element validation passes (referential integrity holds; cohort shares sum to 1.0; `null_rate` is zero on the FK column).

2. Construct a `BusinessEventGenerator` rooted at `customer`. Pull 100 events and confirm:
   - Every event has exactly one `customer` parent row.
   - The number of `order` rows per event is one of `{1, 10, 100}` matching the multipliers.
   - Across 100 events, the proportion of each cohort lands within the declared `share` bands (10%, 40%, 50%).
   - Each `order.customer_id` equals its containing event's `customer.id`.
   - Each `order.id` starts with the `customer_id` value followed by `-` and 6 alphanumeric characters.

3. Manually corrupt the v2 yaml with a positive `null_rate` on the FK column and re-parse; expect the `NullRateOnRelationColumnValidator` error naming `order.customer_id`.

4. Manually corrupt the cohort buckets so they sum to 0.7 and re-parse; expect the `CohortBucketSharesValidator` error naming `order.customer_id` and the wrong total.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §1 relations as inline parent-fk-ref column type | Tasks 2, 3, 7 |
| §1 cohort buckets attach to FK column | Tasks 2, 8 |
| §1 cohort sampling logic | Task 8 |
| §1 key-suffix as cross-column reference | Task 6 |
| §1 GenerationContext extension for rowSoFar / parentRow | Tasks 4, 5 |
| §2 business-event tree (parent + transitive children) | Task 11 |
| §6 cross-element rules: relation referential integrity, null_rate not on relation columns | Task 10 |
| Project rule: rich error messages | Tasks 6, 7, 8, 10, 11 |

**Out of scope of this plan (deferred to later plans):**
- `update_ratio` execution: actually picking already-emitted keys (Plan 4 — needs scenarios that drive writes).
- Affinity column annotation (Plan 5 — provisioning).
- Transaction wrapping of business events (Plan 5 — KV target).
- Scenario engine (Plan 4).
- Cross-row state across runs (Plan 6 — state persistence).

---

## Critical files (forward references for Plan 4+)

- `generation/BusinessEventGenerator.kt` — Plan 4's scenario engine drives `next()` at the configured rate and wraps the returned event in a transaction (Plan 5).
- `config/ValueSourceSpec.kt` — Plan 4 may add an `affinity: true` annotation to `ColumnSpec` for Plan 5's provisioning.
- `generation/CohortSampler.kt` — once Plan 6 introduces state persistence, the cohort assignment for a parent should be remembered across runs (today the sampler is in-memory only).
