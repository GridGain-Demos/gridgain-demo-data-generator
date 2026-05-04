# Data Generator — Plan 10: State Persistence

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec §6 (state file). Per-schema sequence positions, the
`KeyRegistry`'s emitted-keys list, and a per-run history index become durable
across `dataGenerate` invocations under
`<demoOutputDirectory>/data-generator/state/state.yaml`. The file carries its
own `schemaVersion`. **No migration support** — a mismatch is a hard error
with remediation guidance, mirroring the plugin's `deployment.yaml` rule
(`DeploymentManager.kt` lines 116–124). Closes the load-bearing requirement
that motivated **F1** (`OutputLayout.ensureBaseDirectories`'s `isDirectory`
guard, closed 2026-05-03).

**Architecture:** All state-related code lives in `data-generator-core` — flavor-agnostic. `Gg8Main` / `Gg9Main` already delegate to `ScenarioRunnerCli`, so the load + save sites land there. Loaded state seeds three consumers: `ValueSourceFactory` (sequence positions), `ScenarioRunner`'s `KeyRegistry` (emitted keys), and the run-history index (append-only). After `runner.run()`, the runner exposes the post-run `KeyRegistry` snapshot and the factory exposes the sequence cursors; the CLI assembles a `GeneratorState` and atomically writes it.

**Tech Stack:** No new dependencies. Reuses the Jackson YAML mapper already wired in core. `Files.move(... ATOMIC_MOVE, REPLACE_EXISTING)` for the write. SnakeYAML pinned at `1.33`.

---

## Pre-execution prerequisites

1. Post-Plan-9 `main` (three subprojects, **170 tests pass**: 162 unit + 8 env-gated integration).
2. F1 closed (2026-05-03): `OutputLayout.ensureBaseDirectories` already creates `state/`. Save path does not need `Files.createDirectories`.
3. `gridgain-demo-client-utils` published to maven local.
4. `CURRENT_DATA_SCHEMA_VERSION` / `CURRENT_OPS_SCHEMA_VERSION` already in `config/ConfiguredVersions.kt`. Plan 10 adds `CURRENT_STATE_SCHEMA_VERSION` alongside.
5. `CorruptedStateException` already in `errors/DomainExceptions.kt` — reused for both version-mismatch and corrupted-yaml paths.

---

## Spec extensions (additive)

```yaml
# state.yaml — written under <demoOutputDirectory>/data-generator/state/
schema_version: 1
sequences:
  - schema_name: customer
    column_name: id
    next_value: 4201
keys:
  - schema_name: customer
    keys: ["1", "2", "3", "4", "5"]
run_history:
  - run_id: 20260504-091215-x9k3pa
    scenario_name: customer-load
    started_at: "2026-05-04T09:12:15Z"
    completed_at: "2026-05-04T09:12:23Z"
    success_count: 200
    error_count: 0
    stop_reason: count reached
```

**No `data.yaml` / `ops.yaml` `schema_version` bump.** State is a new file
with its own version; the user-facing config files are untouched.

---

## Target architecture

```
data-generator-core/
└── src/main/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   └── ConfiguredVersions.kt          # ADD CURRENT_STATE_SCHEMA_VERSION
    ├── state/                              # NEW package
    │   ├── GeneratorState.kt               # NEW: top-level data class
    │   ├── SequenceState.kt                # NEW: per-schema-column position
    │   ├── KeyRegistryState.kt             # NEW: per-schema list of emitted keys
    │   ├── RunHistoryEntry.kt              # NEW: single run record
    │   └── StatePersister.kt               # NEW: load/save with version check
    ├── generation/
    │   ├── SequenceValueSource.kt          # MODIFY: `currentNext` accessor; optional initial position
    │   ├── ValueSourceFactory.kt           # MODIFY: take loadedState; expose sequence cursors
    │   └── KeyRegistry.kt                  # MODIFY: snapshot()/restore() + (small) accessor surface
    ├── scenario/
    │   └── ScenarioRunner.kt               # MODIFY: accept pre-populated KeyRegistry; expose post-run snapshot
    └── cli/
        └── ScenarioRunnerCli.kt            # MODIFY: load → run → save
```

Cross-package additions are intentional: `ValueSourceFactory` lives in
`generation/`, `KeyRegistry` lives in `scenario/`, and the persister sits in
the new `state/` package above both. Wiring happens once in
`ScenarioRunnerCli`.

**Key types (in prose):**

- `GeneratorState(schemaVersion: Int, sequences: List<SequenceState>, keys: List<KeyRegistryState>, runHistory: List<RunHistoryEntry>)`
- `SequenceState(schemaName: String, columnName: String, nextValue: Long)`
- `KeyRegistryState(schemaName: String, keys: List<String>)`
- `RunHistoryEntry(runId: String, scenarioName: String, startedAt: String, completedAt: String?, successCount: Long, errorCount: Long, stopReason: String)`

**Key-type round-trip note.** `KeyRegistry.register(...)` accepts `Any` and today receives `Long` (from sequences) and `String` (from key-suffix / data-faker). Persisted keys are `List<String>` for JSON-safety; non-string scalars round-trip via `toString()` and come back as `String`. The registry treats keys opaquely for sampling and `update_ratio` re-emission, so this is sound. Documented as follow-up **F11** (Task 11) for any future consumer that needs original numeric type fidelity.

---

## Tasks

### Task 1: Add `CURRENT_STATE_SCHEMA_VERSION` constant

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersionsTest.kt`

- [ ] **Step 1: Write the failing test** in `ConfiguredVersionsTest.kt`

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ConfiguredVersionsTest {
    @Test fun `state schema version is pinned at 1`() {
        assertThat(CURRENT_STATE_SCHEMA_VERSION).isEqualTo(1)
    }
    @Test fun `data and ops versions remain at 2`() {
        assertThat(CURRENT_DATA_SCHEMA_VERSION).isEqualTo(2)
        assertThat(CURRENT_OPS_SCHEMA_VERSION).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Verify FAIL** — `./gradlew :data-generator-core:test --tests '*ConfiguredVersionsTest'`.

- [ ] **Step 3: Add the constant** to `ConfiguredVersions.kt`. Above-or-below the existing two; comment must record the no-migration policy and reference `DeploymentManager.kt` lines 116–124 as the parallel pattern.

```kotlin
/**
 * State file ("`<demoOutputDirectory>/data-generator/state/state.yaml`") schema version.
 * **No migration support.** A mismatched `schemaVersion` in `state.yaml` is a hard error:
 * `StatePersister.load` throws `CorruptedStateException` with remediation guidance to tear
 * down and re-run from clean state. Mirrors the plugin's `deployment.yaml` policy
 * (`DeploymentManager.kt` lines 116–124).
 */
const val CURRENT_STATE_SCHEMA_VERSION: Int = 1
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersionsTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): add CURRENT_STATE_SCHEMA_VERSION = 1 (Plan 10 Task 1)

Third versioned schema in the data generator alongside data and ops. Per
spec §6 the state file has its own schemaVersion with no migration support;
mismatches are hard errors with remediation guidance.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Define state data classes

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/SequenceState.kt`
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/KeyRegistryState.kt`
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/RunHistoryEntry.kt`
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/GeneratorState.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/state/GeneratorStateRoundTripTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

```kotlin
package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class GeneratorStateRoundTripTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test fun `round-trips through yaml with snake_case`() {
        val original = GeneratorState(
            schemaVersion = CURRENT_STATE_SCHEMA_VERSION,
            sequences = listOf(SequenceState("customer", "id", 4201)),
            keys = listOf(KeyRegistryState("customer", listOf("1", "2", "3"))),
            runHistory = listOf(RunHistoryEntry(
                runId = "20260504-091215-x9k3pa",
                scenarioName = "customer-load",
                startedAt = "2026-05-04T09:12:15Z",
                completedAt = "2026-05-04T09:12:23Z",
                successCount = 200, errorCount = 0, stopReason = "count reached",
            )),
        )
        val yaml = mapper.writeValueAsString(original)
        assertThat(yaml).contains("schema_version: 1", "schema_name: \"customer\"",
                                 "column_name: \"id\"", "next_value: 4201",
                                 "run_id:", "started_at:", "stop_reason:")
        assertThat(mapper.readValue(yaml, GeneratorState::class.java)).isEqualTo(original)
    }
}
```

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Create the four data classes.** All are plain Kotlin `data class`es with `@JsonProperty(...)` for every field that needs snake_case mapping.

```kotlin
// SequenceState.kt
data class SequenceState(
    @JsonProperty("schema_name") val schemaName: String,
    @JsonProperty("column_name") val columnName: String,
    @JsonProperty("next_value") val nextValue: Long,
)

// KeyRegistryState.kt — keys are String for JSON-safety; F11 covers type fidelity beyond sampling.
data class KeyRegistryState(
    @JsonProperty("schema_name") val schemaName: String,
    val keys: List<String>,
)

// RunHistoryEntry.kt — completedAt is the only documented null case (run aborted before
// runner.run() returns); whitelisted per project's "no nullable" rule.
data class RunHistoryEntry(
    @JsonProperty("run_id") val runId: String,
    @JsonProperty("scenario_name") val scenarioName: String,
    @JsonProperty("started_at") val startedAt: String,
    @JsonProperty("completed_at") val completedAt: String?,
    @JsonProperty("success_count") val successCount: Long,
    @JsonProperty("error_count") val errorCount: Long,
    @JsonProperty("stop_reason") val stopReason: String,
)

// GeneratorState.kt — top-level. Written by StatePersister.save after each runner.run().
data class GeneratorState(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val sequences: List<SequenceState>,
    val keys: List<KeyRegistryState>,
    @JsonProperty("run_history") val runHistory: List<RunHistoryEntry>,
)
```

(All four files share `package com.gridgain.demo.datagen.state` and `import com.fasterxml.jackson.annotation.JsonProperty`.)

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): add GeneratorState data classes (Plan 10 Task 2)`. Body: aggregates SequenceState (per-schema-column cursor), KeyRegistryState (per-schema emitted keys), RunHistoryEntry (per-run record). Snake_case yaml. Keys persist as `List<String>`; F11 captures type-fidelity-beyond-sampling. End with the standard `Co-Authored-By` trailer.

---

### Task 3: `StatePersister` — load + save + version check

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/StatePersister.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/state/StatePersisterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.state

import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.errors.CorruptedStateException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class StatePersisterTest {

    private fun sample(version: Int = CURRENT_STATE_SCHEMA_VERSION) = GeneratorState(
        schemaVersion = version,
        sequences = listOf(SequenceState("customer", "id", 17)),
        keys = listOf(KeyRegistryState("customer", listOf("1", "2"))),
        runHistory = listOf(
            RunHistoryEntry(
                runId = "20260504-091215-x9k3pa",
                scenarioName = "customer-load",
                startedAt = "2026-05-04T09:12:15Z",
                completedAt = "2026-05-04T09:12:23Z",
                successCount = 200, errorCount = 0, stopReason = "count reached",
            )
        ),
    )

    @Test fun `load returns null when state file is absent`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        assertThat(StatePersister().load(target)).isNull()
    }

    @Test fun `save then load round-trips`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        val original = sample()
        StatePersister().save(original, target)
        val loaded = StatePersister().load(target)
        assertThat(loaded).isEqualTo(original)
    }

    @Test fun `mismatched schemaVersion throws CorruptedStateException with remediation`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("state.yaml")
        StatePersister().save(sample(version = 999), target)
        assertThatThrownBy { StatePersister().load(target) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining("state-file format does not support migration")
            .hasMessageContaining(target.toString())
            .hasMessageContaining("matches the plugin's `deployment.yaml` rule")
    }

    @Test fun `corrupted yaml throws CorruptedStateException with file path`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        Files.writeString(target, "schema_version: not-an-int\nthis is broken yaml")
        assertThatThrownBy { StatePersister().load(target) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining(target.toString())
    }

    @Test fun `save uses atomic move via temp file`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        StatePersister().save(sample(), target)
        // Tmp must not linger after a successful save.
        assertThat(Files.exists(dir.resolve("state.yaml.tmp"))).isFalse()
        assertThat(Files.exists(target)).isTrue()
    }
}
```

- [ ] **Step 2: Run and verify FAIL** —
  `./gradlew :data-generator-core:test --tests '*StatePersisterTest'` (class doesn't exist).

- [ ] **Step 3: Create the persister**

```kotlin
package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.errors.CorruptedStateException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Reads and writes `state.yaml`. **No migration support** per spec §6:
 * a `schemaVersion` mismatch is a hard error, mirroring the plugin's
 * `deployment.yaml` policy in `DeploymentManager.kt`.
 *
 * Save is atomic: the new contents go to `state.yaml.tmp`, then
 * `Files.move(... ATOMIC_MOVE, REPLACE_EXISTING)` swaps it in. If the
 * filesystem doesn't support atomic move, falls back to a non-atomic
 * `REPLACE_EXISTING` move. This matches the plugin's writer pattern.
 */
class StatePersister {

    private val mapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    /**
     * Returns `null` when `stateFile` does not exist (first-run case). Any other
     * failure mode — corrupted yaml, mismatched `schemaVersion` — throws
     * `CorruptedStateException` with remediation guidance.
     */
    fun load(stateFile: Path): GeneratorState? {
        if (!Files.exists(stateFile)) return null

        val state: GeneratorState = try {
            mapper.readValue(stateFile.toFile(), GeneratorState::class.java)
        } catch (e: JsonMappingException) {
            throw CorruptedStateException(
                "Failed to parse state file '$stateFile'. " +
                "The data generator's state-file format does not support migration; " +
                "this matches the plugin's `deployment.yaml` rule. " +
                "Remediation: tear down '$stateFile' and rerun from clean state.",
                e,
            )
        } catch (e: Exception) {
            throw CorruptedStateException(
                "Failed to read state file '$stateFile'. " +
                "Remediation: tear down '$stateFile' and rerun from clean state.",
                e,
            )
        }

        if (state.schemaVersion != CURRENT_STATE_SCHEMA_VERSION) {
            throw CorruptedStateException(
                "state.yaml at '$stateFile' has schema_version=${state.schemaVersion}, " +
                "but this generator expects ${CURRENT_STATE_SCHEMA_VERSION}. " +
                "Tear down '$stateFile' and rerun from clean state. The data generator's " +
                "state-file format does not support migration; this matches the plugin's " +
                "`deployment.yaml` rule."
            )
        }

        return state
    }

    /**
     * Atomically writes `state` to `stateFile`. The parent directory is assumed
     * to exist (Plan 9 + F1 left `OutputLayout.ensureBaseDirectories` responsible
     * for that). Falls back to a non-atomic move if the filesystem rejects
     * `ATOMIC_MOVE`.
     */
    fun save(state: GeneratorState, stateFile: Path) {
        val tmp = stateFile.resolveSibling(stateFile.fileName.toString() + ".tmp")
        mapper.writeValue(tmp.toFile(), state)
        try {
            Files.move(
                tmp, stateFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Some filesystems (notably old SMB/NFS, occasionally tmpfs across mounts) reject
            // ATOMIC_MOVE. The fallback is non-atomic but still REPLACE_EXISTING — a partial
            // failure here means the next run sees an absent state.yaml and starts fresh,
            // which is correct first-run behavior.
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
```

- [ ] **Step 4: Verify PASS** — all five tests green.

- [ ] **Step 5: Commit** — message: `feat(datagen): StatePersister load/save with hard-fail version check (Plan 10 Task 3)`. Body: load returns null on first run; mismatched `schemaVersion` or corrupt yaml throws `CorruptedStateException` with spec §6 remediation; save uses atomic move with non-atomic fallback. End with `Co-Authored-By` trailer.

---

### Task 4: Extend `SequenceValueSource` with `currentNext` accessor + initial position

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSource.kt`
- Modify: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/generation/SequenceValueSourceTest.kt`

- [ ] **Step 1: Add failing tests** to `SequenceValueSourceTest`

```kotlin
@Test fun `currentNext exposes the next value to be emitted`() {
    val s = SequenceValueSource(start = 10, step = 3)
    assertThat(s.currentNext).isEqualTo(10L)
    s.next(emptyContext())
    assertThat(s.currentNext).isEqualTo(13L)
}

@Test fun `initialPosition overrides start`() {
    val s = SequenceValueSource(start = 10, step = 3, initialPosition = 100)
    assertThat(s.currentNext).isEqualTo(100L)
    val first = s.next(emptyContext())
    assertThat(first).isEqualTo(100L)
    assertThat(s.currentNext).isEqualTo(103L)
}

@Test fun `initialPosition of null falls back to start`() {
    val s = SequenceValueSource(start = 10, step = 3, initialPosition = null)
    assertThat(s.currentNext).isEqualTo(10L)
}

private fun emptyContext() = GenerationContext(
    faker = net.datafaker.Faker(),
    rowSoFar = mutableMapOf(),
    parentRow = null,
)
```

(Helper `emptyContext()` may already exist in the test file; if so, reuse it.)

- [ ] **Step 2: Run and verify FAIL** —
  `./gradlew :data-generator-core:test --tests '*SequenceValueSourceTest'`.

- [ ] **Step 3: Modify `SequenceValueSource`**

```kotlin
package com.gridgain.demo.datagen.generation

class SequenceValueSource(
    start: Long,
    private val step: Long,
    initialPosition: Long? = null,
) : ValueSource {

    private var nextValue: Long = initialPosition ?: start

    /** The value that will be returned by the next call to `next()`. Used by `ValueSourceFactory`
     *  to capture sequence cursors at end of run for `state.yaml`. */
    val currentNext: Long get() = nextValue

    override fun next(ctx: GenerationContext): Any {
        val out = nextValue
        nextValue += step
        return out
    }
}
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): SequenceValueSource exposes currentNext + initialPosition (Plan 10 Task 4)`. Body: `currentNext` lets the factory snapshot cursors at end of run; `initialPosition` lets it seed sequences from a loaded state. Default behavior unchanged. End with `Co-Authored-By` trailer.

---

### Task 5: Extend `KeyRegistry` with `snapshot` / `restore`

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistry.kt`
- Modify: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/scenario/KeyRegistryTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test fun `snapshot returns empty list when registry is empty`() {
    assertThat(KeyRegistry().snapshot()).isEmpty()
}

@Test fun `snapshot returns one entry per registered schema`() {
    val r = KeyRegistry()
    r.register("customer", 1L)
    r.register("customer", 2L)
    r.register("order", "o-100")
    val snap = r.snapshot()
    assertThat(snap).hasSize(2)
    assertThat(snap.first { it.schemaName == "customer" }.keys)
        .containsExactly("1", "2")
    assertThat(snap.first { it.schemaName == "order" }.keys)
        .containsExactly("o-100")
}

@Test fun `restore populates registry from a saved list`() {
    val r = KeyRegistry()
    r.restore(listOf(KeyRegistryState("customer", listOf("1", "2", "3"))))
    assertThat(r.size("customer")).isEqualTo(3)
    val rng = java.util.Random(0L)
    assertThat(r.sample("customer", rng)).isIn("1", "2", "3")
}

@Test fun `restore does not duplicate already-registered keys`() {
    val r = KeyRegistry()
    r.register("customer", "1")
    r.restore(listOf(KeyRegistryState("customer", listOf("1", "2"))))
    assertThat(r.size("customer")).isEqualTo(2)
}
```

- [ ] **Step 2: Run and verify FAIL**.

- [ ] **Step 3: Modify `KeyRegistry`** — keep `register` / `sample` / `size` as-is. Add the import `com.gridgain.demo.datagen.state.KeyRegistryState` and append:

```kotlin
/**
 * Captures the current registry as a list of `KeyRegistryState`, one entry per schema.
 * Keys are converted to `String` via `toString()` for JSON-safety. Schemas are
 * deterministically ordered for stable yaml output. F11 covers type-fidelity beyond
 * sampling.
 */
fun snapshot(): List<KeyRegistryState> = keysBySchema.entries
    .sortedBy { it.key }
    .map { (schema, keys) -> KeyRegistryState(schema, keys.map { it.toString() }) }

/**
 * Populates the registry from a previously persisted snapshot. Idempotent: already-
 * registered keys are not duplicated. Loaded keys are `String`; runtime `register(...)`
 * calls in the same process keep adding their original types — uniqueness is by
 * `Any.equals`, so `Long(1)` and `String("1")` stay distinct. Acceptable because reload
 * only happens at startup before any runtime registers fire.
 */
fun restore(states: List<KeyRegistryState>) {
    states.forEach { state -> state.keys.forEach { key -> register(state.schemaName, key) } }
}
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): KeyRegistry snapshot/restore (Plan 10 Task 5)`. Body: `snapshot()` returns `List<KeyRegistryState>` with deterministic schema ordering; `restore()` rehydrates idempotently. End with `Co-Authored-By` trailer.

---

### Task 6: Extend `ValueSourceFactory` to seed sequences and expose cursors

**Design note.** `factory.build(column)` today only sees a single column. Keying sequences by `(schemaName, columnName)` for state lookup and snapshot requires the call site (`RowGenerator`) to thread the schema name in. The single-arg `build(column)` overload is removed in this same task; both call sites (`RowGenerator` + the test fixture) migrate together.

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactory.kt`
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/generation/RowGenerator.kt`
- Modify: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/generation/ValueSourceFactoryTest.kt`

- [ ] **Step 1: Add failing tests**

```kotlin
@Test fun `seeds SequenceValueSource from loadedState`(@TempDir dir: Path) {
    val column = ColumnSpec(
        name = "id", key = true, valueSource = SequenceSpec(start = 1, step = 1),
        nullRate = 0.0, affinity = false,
    )
    val state = GeneratorState(
        schemaVersion = CURRENT_STATE_SCHEMA_VERSION,
        sequences = listOf(SequenceState("customer", "id", 5000L)),
        keys = emptyList(), runHistory = emptyList(),
    )
    val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 0L, loadedState = state)
    val vs = factory.build(schemaName = "customer", column = column) as SequenceValueSource
    assertThat(vs.currentNext).isEqualTo(5000L)
}

@Test fun `omitting loadedState preserves prior factory behavior`(@TempDir dir: Path) {
    val column = ColumnSpec(
        name = "id", key = true, valueSource = SequenceSpec(start = 7, step = 1),
        nullRate = 0.0, affinity = false,
    )
    val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 0L)
    val vs = factory.build(schemaName = "customer", column = column) as SequenceValueSource
    assertThat(vs.currentNext).isEqualTo(7L)
}

@Test fun `snapshotSequences returns SequenceState entries for all built sequences`(@TempDir dir: Path) {
    val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 0L)
    val cIdCol = ColumnSpec(
        name = "id", key = true, valueSource = SequenceSpec(start = 1, step = 1),
        nullRate = 0.0, affinity = false,
    )
    val cIdSrc = factory.build(schemaName = "customer", column = cIdCol) as SequenceValueSource
    cIdSrc.next(emptyContext()); cIdSrc.next(emptyContext())  // advance to 3
    val snap = factory.snapshotSequences()
    assertThat(snap).containsExactly(SequenceState("customer", "id", 3L))
}
```

(Reuse the existing `emptyContext()` helper or inline one as in Task 4.)

- [ ] **Step 2: Run and verify FAIL**.

- [ ] **Step 3: Modify `ValueSourceFactory`**

```kotlin
package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.ValueSourceSpec
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.SequenceState
import java.nio.file.Path
import java.util.Random

class ValueSourceFactory(
    private val yamlDataRoot: Path,
    private val seed: Long,
    private val uniqueMaxRetries: Int = 1000,
    private val loadedState: GeneratorState? = null,
) {

    /** `(schemaName, columnName) → built SequenceValueSource`, populated by `build`. */
    private val sequencesByKey: MutableMap<Pair<String, String>, SequenceValueSource> = mutableMapOf()

    /** Builds a `ValueSource` for `column` in `schemaName`. Sequence sources additionally
     *  consult `loadedState` for a saved cursor and register themselves for `snapshotSequences`. */
    fun build(schemaName: String, column: ColumnSpec): ValueSource {
        val core = buildCore(schemaName, column)
        return if (column.nullRate > 0.0) {
            NullRateApplicator(core, column.nullRate, Random(seed + column.name.hashCode()))
        } else {
            core
        }
    }

    /** Returns one `SequenceState` per `SequenceValueSource` built since this factory was
     *  constructed. Used by `ScenarioRunnerCli` to capture cursors at end of run. */
    fun snapshotSequences(): List<SequenceState> = sequencesByKey.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (k, src) -> SequenceState(k.first, k.second, src.currentNext) }

    private fun buildCore(schemaName: String, column: ColumnSpec): ValueSource = when (val spec = column.valueSource) {
        is SequenceSpec -> {
            val savedNext = loadedState?.sequences
                ?.firstOrNull { it.schemaName == schemaName && it.columnName == column.name }
                ?.nextValue
            val src = SequenceValueSource(
                start = spec.start,
                step = spec.step,
                initialPosition = savedNext,
            )
            sequencesByKey[schemaName to column.name] = src
            src
        }
        is DataFakerSpec -> DataFakerValueSource(spec.expression)
        is UniqueSpec -> UniqueValueSource(spec.expression, maxRetries = uniqueMaxRetries)
        is WeightedChoiceSpec -> WeightedChoiceValueSource(spec.choices, seed = seed + column.name.hashCode())
        is YamlDataSpec -> YamlBackedValueSource(
            path = yamlDataRoot.resolve(spec.path),
            key = spec.key,
            random = Random(seed + column.name.hashCode()),
        )
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
    }
}
```

- [ ] **Step 4: Update `RowGenerator`** — change the single line that calls the factory:

```kotlin
private val sources: List<Pair<String, ValueSource>> =
    schema.columns.map { it.name to factory.build(schemaName = schema.name, column = it) }
```

- [ ] **Step 5: Migrate test fixtures** — search for `factory.build(` in `data-generator-core/src/test/`. Pass `schemaName = "<schema>"` explicitly at every call site (use the schema's `name` from the surrounding fixture). Tests using `RowGenerator` need no change — `RowGenerator` already threads it.

- [ ] **Step 6: Verify PASS** — `./gradlew :data-generator-core:test --tests '*ValueSourceFactoryTest' --tests '*RowGeneratorTest'` plus a full `:data-generator-core:test` to catch any missed call sites.

- [ ] **Step 7: Commit** — message: `feat(datagen): ValueSourceFactory threads schemaName + state-aware sequences (Plan 10 Task 6)`. Body: `build(schemaName, column)` seeds `SequenceValueSource` from `loadedState`'s cursor and registers it for `snapshotSequences()`; `RowGenerator` threads `schema.name` in; single-arg `build(column)` removed. End with `Co-Authored-By` trailer.

---

### Task 7: `ScenarioRunner` accepts a pre-populated `KeyRegistry` and exposes its post-run snapshot

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`
- Modify: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerTest.kt`

- [ ] **Step 1: Add two failing tests** in `ScenarioRunnerTest.kt`:
  1. `pre-populated KeyRegistry is used for read sampling` — build a `KeyRegistry` with `restore(listOf(KeyRegistryState("customer", listOf("preloaded-1", "preloaded-2"))))`, inject via the new constructor parameter, run a `readRatio = 1.0`, `count = 5` scenario against a `ReadCapturingTarget`, assert all 5 reads come from the preloaded set.
  2. `keyRegistrySnapshot exposes the post-run registry contents` — run a `count = 3` write-only scenario, assert `runner.keyRegistrySnapshot()` returns one `KeyRegistryState` for `"customer"` with three keys.

  Helpers (`ReadCapturingTarget`, `NoOpTarget`, `scenario(...)`, `dataConfig()`, `trivialGenerator()`, `sequenceGenerator()`) follow the patterns in `ScenarioRunnerTest` / `ScenarioRunnerExtendedTest`.

- [ ] **Step 2: Verify FAIL** — constructor parameter and `keyRegistrySnapshot` don't exist.

- [ ] **Step 3: Modify `ScenarioRunner`** — replace `private val keyRegistry = KeyRegistry()` with a constructor parameter `private val keyRegistry: KeyRegistry = KeyRegistry()`; add the accessor:

```kotlin
/** Captures the post-run registry contents for `state.yaml`. Safe to call multiple times. */
fun keyRegistrySnapshot(): List<KeyRegistryState> = keyRegistry.snapshot()
```

Imports: `com.gridgain.demo.datagen.state.KeyRegistryState`. Everything else in the class stays as-is.

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): ScenarioRunner accepts injected KeyRegistry + exposes snapshot (Plan 10 Task 7)`. Body: optional `keyRegistry` constructor parameter (defaults to fresh registry); `keyRegistrySnapshot()` exposes the per-schema list post-run for state persistence. End with `Co-Authored-By` trailer.

---

### Task 8: Wire `ScenarioRunnerCli` for load → run → save

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCli.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCliStateTest.kt` (NEW)

- [ ] **Step 1: Write the failing integration-style test (uses `InMemoryTarget`)**

```kotlin
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerCliStateTest {

    @Test fun `state survives across two runs against an InMemoryTarget`(@TempDir dir: Path) {
        // Fixture setup: write a minimal data.yaml + ops.yaml + endpoints + a count=10 scenario.
        // (Reuse helpers from existing test fixtures; abbreviated here for plan brevity.)
        val outputDir = dir.resolve("output")
        Files.createDirectories(outputDir)
        val args = buildCliArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")

        // Run 1.
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution1 = ScenarioRunnerCli.resolve(args, logger)
        val target1 = InMemoryTarget(supportsReads = true, supportsTransactions = false)
        val r1 = ScenarioRunnerCli.run(args, resolution1, target1, logger)
        assertThat(r1.successCount).isEqualTo(10L)

        val stateFile = outputDir.resolve("data-generator/state/state.yaml")
        val state1 = StatePersister().load(stateFile)!!
        assertThat(state1.sequences.first { it.schemaName == "customer" }.nextValue).isEqualTo(11L)
        assertThat(state1.keys.first { it.schemaName == "customer" }.keys).hasSize(10)
        assertThat(state1.runHistory).hasSize(1)

        // Run 2 — fresh CLI invocation, same outputDir.
        val resolution2 = ScenarioRunnerCli.resolve(args, logger)
        val target2 = InMemoryTarget(supportsReads = true, supportsTransactions = false)
        val r2 = ScenarioRunnerCli.run(args, resolution2, target2, logger)
        assertThat(r2.successCount).isEqualTo(10L)

        val state2 = StatePersister().load(stateFile)!!
        // Sequence continued: 11 → 21.
        assertThat(state2.sequences.first { it.schemaName == "customer" }.nextValue).isEqualTo(21L)
        // Keys append, no resets.
        assertThat(state2.keys.first { it.schemaName == "customer" }.keys).hasSize(20)
        // Run history grew.
        assertThat(state2.runHistory).hasSize(2)
        assertThat(state2.runHistory.map { it.scenarioName }).containsExactly("writes", "writes")
    }
}
```

- [ ] **Step 2: Run and verify FAIL** (state file isn't being written / read).

- [ ] **Step 3: Modify `ScenarioRunnerCli.run`**

```kotlin
fun run(parsed: CliArgs, resolution: Resolution, target: Target, logger: DataGenLogger): ScenarioResult {
    val layout = OutputLayout(parsed.outputDir)
    layout.ensureBaseDirectories()

    val persister = StatePersister()
    val loadedState: GeneratorState? = persister.load(layout.stateFile)
    if (loadedState != null) {
        logger.lifecycle(
            "loaded prior state: ${loadedState.sequences.size} sequence cursors, " +
            "${loadedState.keys.sumOf { it.keys.size }} keys, " +
            "${loadedState.runHistory.size} prior runs (from ${layout.stateFile})."
        )
    }

    val factory = ValueSourceFactory(
        yamlDataRoot = parsed.dataFile.parent,
        seed = 0L,
        loadedState = loadedState,
    )
    val rootSchema = resolution.scenario.rootSchemas.first()
    val gen = BusinessEventGenerator(
        data = resolution.parsedConfig.data,
        rootSchemaName = rootSchema,
        factory = factory,
        faker = Faker(),
        cohortSeed = 0L,
    )

    val keyRegistry = KeyRegistry()
    if (loadedState != null) keyRegistry.restore(loadedState.keys)

    val runner = ScenarioRunner(
        scenario = resolution.scenario,
        data = resolution.parsedConfig.data,
        generator = gen,
        target = target,
        keyRegistry = keyRegistry,
    )

    val startedAt = Instant.now()
    val result = runner.run()
    val completedAt = Instant.now()
    if (target is AutoCloseable) target.close()

    val runId = RunId.generate()
    val resultFile = layout.resultFile(runId)
    ScenarioResult.write(result, resultFile)

    val newState = GeneratorState(
        schemaVersion = CURRENT_STATE_SCHEMA_VERSION,
        sequences = factory.snapshotSequences(),
        keys = runner.keyRegistrySnapshot(),
        runHistory = (loadedState?.runHistory ?: emptyList()) + RunHistoryEntry(
            runId = runId,
            scenarioName = resolution.scenario.name,
            startedAt = startedAt.toString(),
            completedAt = completedAt.toString(),
            successCount = result.successCount,
            errorCount = result.errorCount,
            stopReason = result.stopReason,
        ),
    )
    persister.save(newState, layout.stateFile)

    logger.lifecycle(
        "scenario '${resolution.scenario.name}' complete: ${result.successCount} successes, " +
        "${result.errorCount} errors, achieved_rate=${result.achievedRate}, " +
        "stop_reason=${result.stopReason}"
    )
    logger.lifecycle("result written to $resultFile")
    logger.lifecycle("state written to ${layout.stateFile}")

    return result
}
```

Imports added: `com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION`,
`com.gridgain.demo.datagen.state.GeneratorState`,
`com.gridgain.demo.datagen.state.RunHistoryEntry`,
`com.gridgain.demo.datagen.state.StatePersister`,
`com.gridgain.demo.datagen.scenario.KeyRegistry`,
`java.time.Instant`.

- [ ] **Step 4: Verify PASS** — `./gradlew :data-generator-core:test --tests '*ScenarioRunnerCliStateTest'`.

- [ ] **Step 5: Commit** — message: `feat(datagen): ScenarioRunnerCli loads state, seeds runtime, persists post-run (Plan 10 Task 8)`. Body: pre-run loads `state.yaml` (null on first run), seeds `ValueSourceFactory.loadedState` and pre-populates `KeyRegistry`; post-run captures sequence cursors + key snapshot, appends a `RunHistoryEntry`, atomically writes the new `GeneratorState`. Integration test verifies sequences continue across two runs against `InMemoryTarget`. End with `Co-Authored-By` trailer.

---

### Task 9: Hard-fail behavior on schema-version mismatch (end-to-end)

**Files:**
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCliVersionMismatchTest.kt` (NEW)

- [ ] **Step 1: Write the test**

```kotlin
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.CorruptedStateException
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerCliVersionMismatchTest {

    @Test fun `pre-existing state with future schemaVersion fails the run with remediation`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output")
        Files.createDirectories(outputDir.resolve("data-generator/state"))
        // Pre-write a state.yaml with a future version.
        val stateFile = outputDir.resolve("data-generator/state/state.yaml")
        StatePersister().save(
            GeneratorState(
                schemaVersion = 999,
                sequences = emptyList(),
                keys = emptyList(),
                runHistory = emptyList(),
            ),
            stateFile,
        )

        val args = buildCliArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution = ScenarioRunnerCli.resolve(args, logger)
        val target = InMemoryTarget(supportsReads = false, supportsTransactions = false)

        assertThatThrownBy { ScenarioRunnerCli.run(args, resolution, target, logger) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining("schema_version=999")
            .hasMessageContaining("expects 1")
            .hasMessageContaining("Tear down")
            .hasMessageContaining("does not support migration")
            .hasMessageContaining("matches the plugin's `deployment.yaml` rule")
    }
}
```

- [ ] **Step 2: Verify PASS** (the wiring from Task 8 already delegates to `StatePersister.load`, which throws). If green, sanity-check by toggling the version constant in `StatePersister.load`'s message to confirm the `hasMessageContaining` chain is actually exercised.

- [ ] **Step 3: Commit** — message: `test(datagen): end-to-end version-mismatch hard-fail (Plan 10 Task 9)`. Body: pre-creates `state.yaml` at `schemaVersion=999`, runs the CLI's `run()` path, asserts `CorruptedStateException` carrying spec §6 remediation. Mirrors the plugin's `deployment.yaml` hard-fail policy. End with `Co-Authored-By` trailer.

---

### Task 10: Live-cluster smoke against `taxi-demo-gcp-8a`

**Goal:** Confirm Plan 10 holds against a real GG8 cluster, with state.yaml
arriving on disk in TaxiDemo's `build/gridgain/output/data-generator/state/`.

- [ ] **Step 1: Publish to maven local**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew publishToMavenLocal
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin
./gradlew publishToMavenLocal
```

- [ ] **Step 2: Run a write-only scenario, observe fresh state.yaml**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/TaxiDemo
./gradlew dataGenerate --scenario customer-load
ls -la build/gridgain/output/data-generator/state/state.yaml
cat build/gridgain/output/data-generator/state/state.yaml
```

Expected:
- `state.yaml` exists.
- `schema_version: 1`.
- `run_history` carries one entry with the just-completed scenario name and
  `success_count > 0`.
- `sequences` contains one entry per sequence-typed column in the touched
  schemas, with `next_value` > the configured `start`.

- [ ] **Step 3: Run the same scenario again, observe sequence continuation**

```bash
./gradlew dataGenerate --scenario customer-load
cat build/gridgain/output/data-generator/state/state.yaml
```

Expected:
- `next_value` for `customer.id` (or whatever the configured key column is)
  is strictly greater than after Run 1.
- `run_history` now carries **two** entries.
- Cluster-side: querying the cluster's `customer` cache shows ~2× the row
  count of Run 1 (modulo `update_ratio` re-emissions).

- [ ] **Step 4: Provoke the version-mismatch hard-fail manually**

```bash
# Edit state.yaml's schema_version line to e.g. 999, then:
./gradlew dataGenerate --scenario customer-load
```

Expected: build fails with `CorruptedStateException` containing the
remediation phrase "Tear down" and pointing to the state.yaml path. Restore
`schema_version: 1` to recover.

If a live cluster isn't available, document the gap in the final report and
arrange a smoke when one is brought up. Plan 10's correctness does not
depend on this step — it's a confirmation, not a verification.

- [ ] **Step 5: Commit any TaxiDemo `.gitignore` updates** (no expected
  changes; state lives under `build/`, already ignored). Skip if no diff.

---

### Task 11: ROADMAP update + open F11

**Files:**
- Modify: `gridgain-demo-data-generator/docs/superpowers/ROADMAP.md`

- [ ] **Step 1: Update ROADMAP**
  - Move Plan 10 from "Remaining Plans" into a new "Plan 10 — State Persistence *(complete)*" section above Plan 9, mirroring Plan 9's structure.
  - Bump the **Last updated** line and the test-count line in **Current State**: `Plans 1–10 implemented. **<bumped>** tests pass (... unit + 8 env-gated integration) ...`. Use the actual `./gradlew clean test` count from Task 12; expect ~185 unit + 8 integration = ~193 total.
  - Add an **F11** entry to "Open Follow-ups":

```
### F11 — KeyRegistry persisted-key type fidelity
*Source: Plan 10 Task 2 / Task 5 final review.*
Persisted keys round-trip via `toString()` and come back as `String`. For
sampling and `update_ratio` re-emission this is fine; for any future
consumer needing the original numeric type (e.g., a SQL-mode UPDATE binding
a typed parameter), introduce a typed `KeyValue` wrapper or record column
types in `KeyRegistryState` alongside the keys.
```

  - F1 stays in "Closed Follow-ups" — Plan 10's section text references "Closes the load-bearing requirement that motivated F1" without re-listing it.

- [ ] **Step 2: Commit** — message: `docs(datagen): roadmap — Plan 10 complete; F11 opened (Plan 10 Task 11)`. Body: records Plan 10 complete (state persistence per spec §6); bumps test-count + Last-updated lines; opens F11 (KeyRegistry persisted-key type fidelity). End with `Co-Authored-By` trailer.

---

### Task 12: Final verification

- [ ] **Step 1: Full clean build + test**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew clean test
```

Expected: BUILD SUCCESSFUL across all three subprojects. ~185 unit tests
green. 8 env-gated integration tests skip when env vars are absent
(unchanged from Plan 9).

- [ ] **Step 2: Visual review checklist**

- `data-generator-core/.../state/` carries 5 files (`GeneratorState`,
  `SequenceState`, `KeyRegistryState`, `RunHistoryEntry`, `StatePersister`).
- `ConfiguredVersions.kt` declares all three constants.
- `SequenceValueSource` exposes `currentNext` and accepts `initialPosition`.
- `KeyRegistry` exposes `snapshot()` and `restore(...)`.
- `ValueSourceFactory.build(schemaName, column)` is the only public build
  signature (no deprecated overloads landed).
- `RowGenerator` threads `schema.name` into the factory.
- `ScenarioRunner` accepts an injected `keyRegistry` and exposes
  `keyRegistrySnapshot()`.
- `ScenarioRunnerCli.run` is the sole load + save site; no other code
  imports `StatePersister`.
- `state.yaml` written by Task 8's integration test deserialises cleanly
  through `StatePersister.load`.
- No `org.gradle.*` imports in any `data-generator-core` source.
- No new SnakeYAML version override; project pin at `1.33` honored.
- The CLI's lifecycle log lines explicitly say `"state written to ..."`
  for downstream observability (Plan 11 will graduate this to an OTel
  `state.persisted` lifecycle event).

- [ ] **Step 3: Run `superpowers:requesting-code-review`** (subagent-driven step)

---

## Self-review

- [ ] All commit messages end with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- [ ] No `org.gradle.*` or `org.apache.ignite.*` imports in `data-generator-core`.
- [ ] One nullable introduced (`RunHistoryEntry.completedAt`) — documented inline.
- [ ] Hard-fail-on-mismatch wording is identical between `StatePersister.load`
      and `ScenarioRunnerCliVersionMismatchTest`'s assertions.
- [ ] No deprecated annotations land (the `@Deprecated` shim discussed in
      Task 6 was rejected in favor of in-place migration of the two call sites).
- [ ] No new dependencies; SnakeYAML stays at `1.33`.
- [ ] `OutputLayout.ensureBaseDirectories` is the only directory-creation site
      for `state/`; Plan 10 doesn't add a parallel `Files.createDirectories` call.
- [ ] Spec §6 remediation phrase ("tear down ... rerun from clean state",
      "matches the plugin's `deployment.yaml` rule") appears verbatim in
      `StatePersister.load`'s exception message.

## Spec coverage audit

| Spec § | Tasks |
|---|---|
| §6 `state.yaml` per-schema sequence positions | 4, 6, 8 |
| §6 `state.yaml` key-emission counts (KeyRegistry) | 5, 7, 8 |
| §6 `state.yaml` run history index | 2, 8 |
| §6 own `schemaVersion` | 1, 2, 3 |
| §6 hard-fail on schemaVersion mismatch with remediation | 3, 9 |
| §6 file lives at `<demoOutputDirectory>/data-generator/state/state.yaml` | 8 (uses `OutputLayout.stateFile`) |
| §13 verification step 7 (state persistence smoke) | 8, 10 |
| §12 future work — `state.yaml` migration | explicitly out of scope; reaffirmed |

**Out of scope:** OTel `state.persisted` lifecycle event (Plan 11);
cohort-assignment persistence (deferred — current `BusinessEventGenerator`
re-rolls cohort buckets per `next()` and the cohort sampler is
seed-deterministic so re-roll across runs is acceptable; revisit if a future
plan introduces a per-event cohort cache); F4 / F7 / F10 / F11
follow-ups; multi-scenario runs.

## Critical files (forward references)

- `data-generator-core/.../state/StatePersister.kt` — single load/save site;
  Plan 11 (OTel) will add a `state.persisted` lifecycle event around `save`.
- `data-generator-core/.../state/GeneratorState.kt` — extension point for
  any future persisted slice (e.g., cohort assignments, scenario-target
  bindings).
- `data-generator-core/.../generation/ValueSourceFactory.kt` — Plan 11 will
  decorate this with metric-emission; the per-(schemaName, columnName)
  registration set up here is reusable.
- `data-generator-core/.../scenario/KeyRegistry.kt` — F11 lands here when a
  future plan needs typed key fidelity.
