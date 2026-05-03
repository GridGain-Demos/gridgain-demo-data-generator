# Data Generator — Plan 7.5: Subproject Split

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `gridgain-demo-data-generator` from a single Gradle project into a multi-project build with three subprojects — `data-generator-core`, `data-generator-gg8`, `data-generator-gg9` — mirroring the structural pattern already in use by `gridgain-demo-client-utils` (`client-finder-common` + `gg8-client-finder` + `gg9-client-finder`). Each per-version subproject carries exactly one of `ignite-core:8.9.18` (GG8) or `ignite-client:9.1.3` (GG9), eliminating the FQN-collision that drove Plan 7's reflection workaround in `Gg9KvTarget`. Closes follow-ups **F8** (plugin per-target classpath dispatch) and **F9** (eliminate reflection helpers).

**Architecture:** GG8's `ignite-core` and GG9's `ignite-api` both ship `org.apache.ignite.client.IgniteClient` and `org.apache.ignite.Ignite` at the same FQN with incompatible APIs. Today both are on the same compile classpath, and the Kotlin compiler resolves GG9's superinterface chain through GG8's `Ignite` first because `ignite-core` precedes `ignite-api` (Plan 7 Option E). Plan 7 worked around this with private `gg9Tables()` / `gg9Transactions()` reflection helpers in `Gg9KvTarget`. After Plan 7.5, each version-specific runtime lives in a sibling subproject with its own `ignite-*` dep, so the FQN collision cannot occur. The plugin's `DataGenerateTask` resolves which flavor to fork by reading the named scenario's resolved target kind out of `ops.yaml` before launch.

**Tech Stack additions:** none new — this plan is restructuring, not new tech. SnakeYAML stays pinned at 1.33; Kotlin/JVM, JDK 17 toolchain, `maven-publish`, the GG external repo, and `mavenLocal()` are all preserved.

---

## Pre-execution prerequisites

1. The data-generator's current `main` branch is at the post-Plan-8 state (148 tests green via `./gradlew clean test`).
2. `gridgain-demo-client-utils` is already published to maven local — `gg8-client-finder:0.5.0-SNAPSHOT` and `gg9-client-finder:0.5.0-SNAPSHOT` resolve.
3. The plugin (`gridgain-demo-gradle-plugin`) currently consumes `com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT` via a `dataGeneratorRuntime` configuration auto-created in `GridGainDemoPlugin.kt` (line ~327).
4. The version of the data-generator stays at `0.0.1-SNAPSHOT` for this plan — no version bump is needed; only the artifact ID changes from a single `gridgain-demo-data-generator` to a triple of `-core`, `-gg8`, `-gg9`.
5. The data-generator's `cli/Main.kt` carries `@file:JvmName("Main")` (Plan 8 fix) — preserve this on each per-version `MainKt` replacement.

---

## Target architecture

```
gridgain-demo-data-generator/                      # multi-project gradle root
├── settings.gradle.kts                            # NEW: include three subprojects
├── build.gradle.kts                               # rewritten: shared config; no source
├── data-generator-core/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/gridgain/demo/datagen/
│       ├── config/                                # ALL files (incl. Gg8KvTargetSpec, Gg9KvTargetSpec)
│       ├── errors/
│       ├── generation/
│       ├── logging/
│       ├── output/
│       ├── runtime/
│       ├── scenario/
│       ├── target/Target.kt
│       ├── target/InMemoryTarget.kt
│       └── cli/
│           ├── CliArgs.kt                         # NEW (extracted from Main.kt)
│           └── ScenarioRunnerCli.kt               # NEW (extracted from Main.kt)
│   └── src/main/resources/schema/{data,ops}/      # JSONSchemas — stay in core
│   └── src/test/kotlin/com/gridgain/demo/datagen/ # config/, generation/, scenario/, etc.
├── data-generator-gg8/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/gridgain/demo/datagen/
│       ├── target/Gg8KvTarget.kt
│       └── cli/Gg8Main.kt                         # NEW (constructs Gg8KvTarget)
│   └── src/test/kotlin/com/gridgain/demo/datagen/target/
│       ├── Gg8KvTargetWriteTest.kt
│       └── Gg8KvTargetReadTest.kt
└── data-generator-gg9/
    ├── build.gradle.kts
    └── src/main/kotlin/com/gridgain/demo/datagen/
        ├── target/Gg9KvTarget.kt                  # reflection helpers REMOVED
        └── cli/Gg9Main.kt                         # NEW (constructs Gg9KvTarget)
    └── src/test/kotlin/com/gridgain/demo/datagen/target/
        ├── Gg9KvTargetWriteTest.kt
        └── Gg9KvTargetReadTest.kt
```

**Maven coordinates after split:**
- `com.gridgain.demo:gridgain-demo-data-generator-core:0.0.1-SNAPSHOT`
- `com.gridgain.demo:gridgain-demo-data-generator-gg8:0.0.1-SNAPSHOT`
- `com.gridgain.demo:gridgain-demo-data-generator-gg9:0.0.1-SNAPSHOT`

**Plugin dispatch:** `DataGenerateTask` parses `ops.yaml` cheaply (a one-time `YAMLMapper().readTree`) to find the named scenario's target's `kind`, then assembles the fork classpath using the matching configuration (`dataGeneratorGg8Runtime` or `dataGeneratorGg9Runtime`) and the matching main class (`com.gridgain.demo.datagen.cli.Gg8Main` or `Gg9Main`). The plugin auto-creates both configurations in `apply()`.

**Why this works structurally (not just syntactically):** the per-version subprojects' main source sets see only their own `ignite-*` jar on the compile classpath — not both. The Kotlin compiler resolves `client.tables()` and `client.transactions()` directly against the right `IgniteClient` interface in each module, so the reflection helpers in `Gg9KvTarget` (Plan 7 Option E) become unnecessary and are removed in Task 5.

**Test count expectation:** 148 tests today, redistributed (~140 in core, ~3 GG8 + ~3 GG9 in the per-version modules — exact numbers may differ by 1–2 depending on how integration test classes are counted).

---

### Task 1: Create the multi-project skeleton

**Files:**
- New: `settings.gradle.kts`
- Modify: `build.gradle.kts` (slim to shared config; no source)

Add `include(":data-generator-core", ":data-generator-gg8", ":data-generator-gg9")` to settings, and slim the root `build.gradle.kts` so subprojects inherit repository declarations + Kotlin/JVM toolchain + `maven-publish` setup. The root project itself owns no source after Task 1; the existing top-level `src/` directory is not touched in this task and is moved in Tasks 2/4/5.

- [ ] **Step 1: Replace `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "GridGain External Repository"
            url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "gridgain-demo-data-generator"

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":data-generator-core",
    ":data-generator-gg8",
    ":data-generator-gg9",
)
```

- [ ] **Step 2: Replace root `build.gradle.kts`**

The root build script declares `kotlin("jvm") version "2.2.20" apply false` at the top level, then in `allprojects { group = "com.gridgain.demo"; version = "0.0.1-SNAPSHOT" }` and `subprojects { ... }` it applies `org.jetbrains.kotlin.jvm` + `maven-publish`, declares the same three repositories the root has today (`mavenCentral`, `mavenLocal`, GridGain external), sets `jvmToolchain(17)` on the kotlin extension, forces SnakeYAML to 1.33 in `configurations.all { resolutionStrategy { force(...) } }`, applies `useJUnitPlatform()` to all `Test` tasks, and creates one `MavenPublication("maven")` per subproject. Set the publication's `artifactId = "gridgain-${project.name}"` so the published coordinate becomes `com.gridgain.demo:gridgain-demo-data-generator-core:0.0.1-SNAPSHOT` (etc.) — matching the existing `gridgain-demo-data-generator` artifact-id pattern.

This shape mirrors `gridgain-demo-client-utils/build.gradle.kts` exactly — defer to it as the reference if a structural detail is ambiguous.

- [ ] **Step 3: Verify the project tree resolves**

```bash
cd gridgain-demo-data-generator
./gradlew projects
```

Expected output mentions `Project ':data-generator-core'`, `Project ':data-generator-gg8'`, `Project ':data-generator-gg9'`. The three subproject directories don't exist yet — the next steps create empty stubs so `./gradlew projects` succeeds.

- [ ] **Step 4: Create empty subproject directories with stub `build.gradle.kts`**

For each of `data-generator-core`, `data-generator-gg8`, `data-generator-gg9`:

```bash
mkdir -p data-generator-core data-generator-gg8 data-generator-gg9
```

Place a one-line `build.gradle.kts` in each (deps come in subsequent tasks):

```kotlin
// data-generator-core/build.gradle.kts (placeholder; Task 2 fills it in)
```

```bash
./gradlew projects
```

Expected: BUILD SUCCESSFUL listing the three subprojects. No source yet; root `src/` is intact.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts data-generator-core/build.gradle.kts data-generator-gg8/build.gradle.kts data-generator-gg9/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(datagen): introduce multi-project skeleton (Plan 7.5 Task 1)

Adds settings.gradle.kts include() entries for data-generator-core,
data-generator-gg8, data-generator-gg9. Slims the root build.gradle.kts
to share Kotlin/JVM, mavenLocal, GridGain external repo, SnakeYAML 1.33
force, and maven-publish setup. Subprojects are still empty; Tasks 2/4/5
move source into them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Move shared source into `data-generator-core/`

**Files:**
- `git mv` (or equivalent — see Step 1 note) the bulk of `src/main/kotlin/com/gridgain/demo/datagen/` and `src/main/resources/schema/` into `data-generator-core/`.
- Modify: `data-generator-core/build.gradle.kts` — declare GG-agnostic deps.

This task moves everything that does **not** depend on `ignite-core`/`ignite-client`. `Gg8KvTarget.kt` and `Gg9KvTarget.kt` and their tests stay at the top level for now and migrate in Tasks 4 and 5 respectively. The `cli/Main.kt` entry point also stays at the top level for now — Task 3 extracts the GG-agnostic plumbing out of it.

`Gg8KvTargetSpec` and `Gg9KvTargetSpec` move with the rest of `config/` into core. They're config classes (deserialized from yaml) and don't depend on any `ignite-*` API.

- [ ] **Step 1: Use `git mv` for source preservation**

The data-generator is a standalone git repo (verify: `git rev-parse --show-toplevel`). All source moves use `git mv` so blame walks through the rename.

For each of these top-level packages under `src/main/kotlin/com/gridgain/demo/datagen/`, run `git mv <pkg> data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/<pkg>`: `config`, `errors`, `generation`, `logging`, `output`, `runtime`, `scenario`. Repeat the same set under `src/test/kotlin/com/gridgain/demo/datagen/` → `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/`.

Also move:
- `src/main/kotlin/com/gridgain/demo/datagen/target/Target.kt` and `.../target/InMemoryTarget.kt` → `data-generator-core/.../target/` (leaving `Gg8KvTarget.kt` and `Gg9KvTarget.kt` behind for Tasks 4/5).
- `src/test/kotlin/com/gridgain/demo/datagen/target/InMemoryTargetTest.kt` → `data-generator-core/.../target/InMemoryTargetTest.kt` (the four Gg8/Gg9 integration tests stay behind for Tasks 4/5).
- `src/main/resources/schema` → `data-generator-core/src/main/resources/schema`.
- `src/test/resources` → `data-generator-core/src/test/resources`.

`mkdir -p` the destination parents before each `git mv`.

- [ ] **Step 2: Write `data-generator-core/build.gradle.kts`**

```kotlin
// data-generator-core — GG-agnostic data generator runtime. No ignite-* deps.
dependencies {
    api("net.datafaker:datafaker:2.5.4")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    api("com.networknt:json-schema-validator:1.5.9")
    api("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}
```

`api` (rather than `implementation`) is intentional — both GG8 and GG9 modules use these libs at compile time and need them transitively.

- [ ] **Step 3: Run core's tests in isolation**

```bash
./gradlew :data-generator-core:test
```

Expected: ~140 tests green. The `cli/Main.kt`, `Gg8KvTarget.kt`, and `Gg9KvTarget.kt` files at the top level are now orphaned (they don't compile because the rest of `src/main/kotlin/` is empty); they'll be removed in Tasks 3, 4, 5. The root project does not produce a jar after this task — the source has all moved.

- [ ] **Step 4: Remove the now-empty top-level `src/main/resources/` and stale `src/test/` parents (if any)**

If `src/main/resources` is empty after the schema move, remove the empty directories. Don't delete `src/main/kotlin/com/gridgain/demo/datagen/` yet — it still holds `Gg8KvTarget.kt`, `Gg9KvTarget.kt`, and `cli/Main.kt`.

```bash
find src -type d -empty -delete
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(datagen): move GG-agnostic source into data-generator-core (Plan 7.5 Task 2)

Uses git mv to preserve history. Moves config/, errors/, generation/,
logging/, output/, runtime/, scenario/, target/{Target,InMemoryTarget}.kt,
schema resources, and corresponding tests into data-generator-core.

Gg8KvTarget.kt, Gg9KvTarget.kt, and cli/Main.kt remain at the top
level and migrate in Tasks 3/4/5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Extract GG-agnostic CLI plumbing into `data-generator-core`

**Files:**
- New: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/CliArgs.kt`
- New: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCli.kt`

Today `cli/Main.kt` does two things: argument parsing (`parseArgs`) and post-target orchestration (parser → scenario lookup → `BusinessEventGenerator` → `ScenarioRunner` → write `result.yaml`). Both are GG-agnostic — they receive an already-constructed `Target`. Pull both into core so `Gg8Main` / `Gg9Main` (Tasks 4 + 5) can share them.

- [ ] **Step 1: Create `CliArgs.kt`**

`CliArgs.kt` is a verbatim move of the existing private `data class CliArgs(...)` and `private fun parseArgs(args)` from the current `Main.kt`, with both made `public` (drop `private`) and lifted to top-level functions/classes. No body changes.

- [ ] **Step 2: Create `ScenarioRunnerCli.kt`**

`ScenarioRunnerCli` is an `object` with two methods that together carry the body of the current `Main.kt`'s `try { ... }` block, split at the point where the `Target` is constructed:

```kotlin
object ScenarioRunnerCli {

    data class Resolution(
        val parsedConfig: ParsedConfig,                // type returned by ConfigurationParser.parse(...)
        val scenario: ScenarioSpec,
        val targetSpec: TargetSpec,
        val keyColumnByName: Map<String, String>,
    )

    /** Sets the gg.demo.client.endpoints system property, parses config, finds the named
     *  scenario + its targetSpec, and computes keyColumnByName. Throws MisconfigurationException
     *  with the same rich messages the current Main.kt uses when scenario/target are missing. */
    fun resolve(parsed: CliArgs, logger: DataGenLogger): Resolution { /* lift from current Main.kt lines 28-48 */ }

    /** Constructs the BusinessEventGenerator + ScenarioRunner, runs the scenario, closes
     *  the target if AutoCloseable, writes result.yaml under <outputDir>/data-generator/runs/<run-id>/,
     *  logs the summary, and returns the ScenarioResult. Lift from current Main.kt lines 63-95. */
    fun run(parsed: CliArgs, resolution: Resolution, target: Target, logger: DataGenLogger): ScenarioResult { /* ... */ }

    fun defaultLogger(): DataGenLogger =
        Slf4jDataGenLogger(LoggerFactory.getLogger("datagen-cli"))
}
```

The body is a mechanical lift from `Main.kt` — no behavioral changes; only the split point at the `Target` construction. `ParsedConfig` is whatever type `ConfigurationParser.parse(...)` returns today; defer to the existing name.

- [ ] **Step 3: Verify core compiles + tests still pass**

```bash
./gradlew :data-generator-core:compileKotlin :data-generator-core:test
```

Expected: green; 140 core tests still pass. `Main.kt` at the top level still doesn't compile (its imports refer to types that have moved), but it's no longer part of any source set after Task 1 slimmed the root build, so `./gradlew :data-generator-core:test` ignores it.

- [ ] **Step 4: Commit**

```bash
git add data-generator-core
git commit -m "$(cat <<'EOF'
refactor(datagen): extract GG-agnostic CLI plumbing into ScenarioRunnerCli (Plan 7.5 Task 3)

Pulls argument parsing into CliArgs.kt and post-target orchestration
into ScenarioRunnerCli.kt — both inside data-generator-core. The
per-flavor Gg8Main / Gg9Main (Tasks 4 and 5) construct the right
Target then delegate to ScenarioRunnerCli.run(...).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Move `Gg8KvTarget` + tests + new `Gg8Main` into `data-generator-gg8/`

**Files:**
- `git mv` `Gg8KvTarget.kt` and its two integration tests into the new subproject.
- New: `data-generator-gg8/build.gradle.kts`
- New: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg8Main.kt`

The GG8 subproject depends on `data-generator-core` for everything outside `target/Gg8KvTarget.kt`, on `gg8-client-finder:0.5.0-SNAPSHOT` for endpoint resolution, and on `ignite-core:8.9.18` for the GG8 thin client. **No GG9 deps** — that's the point.

- [ ] **Step 1: Move sources**

`git mv` `Gg8KvTarget.kt` from `src/main/kotlin/.../target/` to `data-generator-gg8/src/main/kotlin/.../target/`. Same for `Gg8KvTargetWriteTest.kt` and `Gg8KvTargetReadTest.kt` under `src/test/kotlin/.../target/` → `data-generator-gg8/src/test/kotlin/.../target/`. `mkdir -p` parent dirs first.

- [ ] **Step 2: Write `data-generator-gg8/build.gradle.kts`**

```kotlin
// data-generator-gg8 — GG8-flavored runtime. ignite-core 8.9.18 + gg8-client-finder. No GG9 deps.
dependencies {
    api(project(":data-generator-core"))
    api("com.gridgain.demo:gg8-client-finder:0.5.0-SNAPSHOT")
    api("org.gridgain:ignite-core:8.9.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // GG8 thin client (Apache Ignite 2.x) reflects on java.nio internals; JDK 17 needs --add-opens. (Plan 6.)
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
```

- [ ] **Step 3: Create `Gg8Main.kt`**

```kotlin
@file:JvmName("Gg8Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
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
        val target = Gg8KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg8) failed: ${e.message}", e)
        exitProcess(1)
    }
}
```

The `@file:JvmName("Gg8Main")` matches Plan 8's fix — without it, Kotlin compiles the top-level `main` to `Gg8MainKt` and the forked JVM (which launches `com.gridgain.demo.datagen.cli.Gg8Main`) won't find the entry point.

- [ ] **Step 4: Run GG8 module's tests**

```bash
./gradlew :data-generator-gg8:test
```

Expected: green. The 3 env-gated GG8 integration tests skip without `DATAGEN_GG8_CLUSTER_NAME` set; only the compile/wiring is exercised here. If `Gg8KvTarget.kt` fails to compile because of an import that previously assumed a flat package, fix the import and re-run — the move shouldn't change any imports because the package declarations stay the same.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(datagen): split out data-generator-gg8 subproject (Plan 7.5 Task 4)

Moves Gg8KvTarget.kt + integration tests under data-generator-gg8.
Adds Gg8Main.kt that constructs Gg8KvTarget and delegates the rest to
ScenarioRunnerCli. The subproject's runtime classpath carries
ignite-core 8.9.18 — no GG9 deps.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Move `Gg9KvTarget` + tests + new `Gg9Main` into `data-generator-gg9/`; remove reflection helpers

**Files:**
- `git mv` `Gg9KvTarget.kt` and its two integration tests.
- New: `data-generator-gg9/build.gradle.kts`
- New: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg9Main.kt`
- Modify: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt` — **remove the `gg9Tables()` and `gg9Transactions()` reflection helpers**.

This is the only task in Plan 7.5 that changes runtime behavior — and only by removing the reflection workaround Plan 7 documented as F9. With GG8's `ignite-core` no longer on this module's compile classpath, `client.tables()` and `client.transactions()` resolve directly against GG9's `IgniteClient`.

- [ ] **Step 1: Move sources**

`git mv` `Gg9KvTarget.kt` and the two integration tests (`Gg9KvTargetWriteTest.kt`, `Gg9KvTargetReadTest.kt`) into `data-generator-gg9/src/main/kotlin/.../target/` and `data-generator-gg9/src/test/kotlin/.../target/`. `mkdir -p` parent dirs first.

- [ ] **Step 2: Write `data-generator-gg9/build.gradle.kts`**

```kotlin
// data-generator-gg9 — GG9-flavored runtime. ignite-client 9.1.3 + gg9-client-finder. No GG8 deps.
// The split (Plan 7.5) is what eliminates the FQN collision that drove Plan 7's reflection workaround.
dependencies {
    api(project(":data-generator-core"))
    api("com.gridgain.demo:gg9-client-finder:0.5.0-SNAPSHOT")
    api("org.gridgain:ignite-client:9.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

// GG9 thin client does not need the java.nio --add-opens that GG8 needs.
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 3: Edit `Gg9KvTarget.kt` — drop reflection helpers**

Three changes:
1. Delete the two private methods `gg9Tables(client)` and `gg9Transactions(client)` (and their KDoc blocks).
2. Remove the imports `org.apache.ignite.table.IgniteTables` and `org.apache.ignite.tx.IgniteTransactions` — these were only used as cast targets in the helpers.
3. Replace the three call sites:
   - `gg9Transactions(ignite).runInTransaction { tx -> ... }` → `ignite.transactions().runInTransaction<Unit> { tx -> ... }` (in `write`).
   - `gg9Tables(ignite).table(schemaName)` → `ignite.tables().table(schemaName)` (in `putRow`).
   - `gg9Tables(ignite).table(cacheName)` → `ignite.tables().table(cacheName)` (in `read`).

The error messages around the `?: throw IllegalStateException(...)` clauses on the table lookups stay verbatim.

- [ ] **Step 4: Create `Gg9Main.kt`**

`Gg9Main.kt` is a structural copy of `Gg8Main.kt` (Task 4 Step 3) with three substitutions:
- `@file:JvmName("Gg9Main")`
- `Gg9KvTargetSpec` instead of `Gg8KvTargetSpec`
- `Gg9KvTarget(...)` instead of `Gg8KvTarget(...)`

Update the mismatched-spec error message to say `gg9-kv` and suggest `Gg8Main` instead, and the failure log line to say `(gg9)`.

- [ ] **Step 5: Run GG9 module's tests**

```bash
./gradlew :data-generator-gg9:test
```

Expected: green. Both env-gated integration tests skip without `DATAGEN_GG9_CLUSTER_NAME`. The compile is the meaningful check — if `client.tables()` resolves cleanly without the helpers, the structural fix is confirmed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(datagen): split out data-generator-gg9 subproject; remove reflection workaround (Plan 7.5 Task 5)

Moves Gg9KvTarget.kt + integration tests under data-generator-gg9.
Adds Gg9Main.kt that constructs Gg9KvTarget and delegates the rest to
ScenarioRunnerCli. The subproject's runtime classpath carries
ignite-client 9.1.3 — no GG8 deps.

Removes the gg9Tables() / gg9Transactions() reflection helpers
introduced in Plan 7 Option E; client.tables() and client.transactions()
now resolve directly because the FQN-shadowing ignite-core 8.x is no
longer on this module's compile classpath. Closes follow-up F9.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Delete the now-empty top-level `src/` directory + old `cli/Main.kt`

**Files:**
- Remove: `src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt`
- Remove: any empty top-level `src/` directories.

After Tasks 2/4/5, the only thing left at the top level is `src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt` (the original, soon-superseded entry point). Delete it.

- [ ] **Step 1: Remove `Main.kt` and empty parents**

```bash
git rm src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt
find src -type d -empty -delete
```

- [ ] **Step 2: Verify clean full build**

```bash
./gradlew clean test
```

Expected: BUILD SUCCESSFUL; ~148 tests across the three subprojects (~140 in core + ~3 GG8 + ~3 GG9, with 5 env-gated integration tests skipped without env vars). Any single missing file shows up here.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore(datagen): remove empty top-level src tree (Plan 7.5 Task 6)

cli/Main.kt is superseded by Gg8Main.kt and Gg9Main.kt in the per-flavor
subprojects. All source has migrated.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Wire `maven-publish` per subproject and verify maven-local

**Files:** all three `build.gradle.kts` files (already configured by Task 1's `subprojects { ... }` block — this task is verification only).

The root build script's `subprojects { apply(plugin = "maven-publish") }` block already creates a `maven` publication per subproject with artifactId `gridgain-${project.name}`. After Tasks 2/4/5 there is real `java` content for each, so the publication produces real jars.

- [ ] **Step 1: Publish to maven-local**

```bash
./gradlew publishToMavenLocal
```

- [ ] **Step 2: Verify all three jars are installed**

```bash
ls ~/.m2/repository/com/gridgain/demo/ | grep gridgain-demo-data-generator
```

Expected:

```
gridgain-demo-data-generator-core
gridgain-demo-data-generator-gg8
gridgain-demo-data-generator-gg9
```

Each directory should contain `0.0.1-SNAPSHOT/` with a jar plus `.pom`.

- [ ] **Step 3: Sanity-check jar contents**

`unzip -l` each per-flavor jar — they should contain only `target/Gg{8,9}KvTarget.class` and `cli/Gg{8,9}Main.class`. All other classes belong in the core jar.

- [ ] **Step 4: Commit (no code changes — verification task)**

If publishing surfaced a wiring issue, fix it in this commit; otherwise this task contributes zero diffs and no commit is needed.

---

### Task 8: Update plugin to dispatch fork classpath + main class per resolved target kind

**Files (in `gridgain-demo-gradle-plugin/`):**
- Modify: `src/main/kotlin/com/gridgain/demo/core/datagen/DataGenerateAction.kt` — add `mainClass` to the request.
- Modify: `src/main/kotlin/com/gridgain/demo/plugin/tasks/DataGenerateTask.kt` — pre-parse ops.yaml; pick the right main class + the right configuration.
- Modify: `src/main/kotlin/com/gridgain/demo/plugin/GridGainDemoPlugin.kt` — auto-create both `dataGeneratorGg8Runtime` and `dataGeneratorGg9Runtime` configurations.
- Modify: `build.gradle.kts` — add deps on the new core jar (`compileOnly`) and the two flavor jars (one each on the two runtime configurations).

This task closes follow-up **F8**.

The pre-parse is intentionally cheap: `YAMLMapper().readTree(opsFile)`, walk to `targets[]`, find the entry whose `name` matches the named scenario's `target` field, and read its `kind`. We don't deserialize into `OpsConfig` here because `OpsConfig` and the `TargetSpec` sealed hierarchy live in the data-generator-core jar — and the plugin only depends on that jar at `compileOnly` (so `TargetSpec` is **not** on the plugin's runtime classpath). Reading the raw JsonNode keeps the plugin out of any schema-version coupling.

- [ ] **Step 1: Update `DataGenerateAction.kt`**

Add a `mainClass: String` field to `DataGenerateRequest` and use it in the launch:

```kotlin
data class DataGenerateRequest(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpointsFile: Path,
    val outputDir: Path,
    val classpath: List<File>,
    val mainClass: String,            // NEW
    val javaExecutable: File,
)
```

In the `cmd = listOf(...)` builder, replace the literal `"com.gridgain.demo.datagen.cli.Main"` with `request.mainClass`.

- [ ] **Step 2: Update `DataGenerateTask.kt`**

Add a private `resolveTargetKind(opsFile, scenarioName): String` helper that uses `YAMLMapper().readTree(opsFile.toFile())` to walk to `scenarios[].name == scenarioName`, find its `.target` value, look up the matching `targets[].name`, and return that target's `.kind` (`"gg8-kv"` or `"gg9-kv"`). Each missing-element path throws `GradleException` with a specific message naming the missing element and the file path. The walk is JsonNode-only — do **not** import any data-generator-core type, because the plugin only consumes the core jar at `compileOnly`.

Then in the existing `run()` body, replace the classpath assembly + `DataGenerateAction` invocation:

```kotlin
val kind = resolveTargetKind(ops, scenarioName)
val (configurationName, mainClass) = when (kind) {
    "gg8-kv" -> "dataGeneratorGg8Runtime" to "com.gridgain.demo.datagen.cli.Gg8Main"
    "gg9-kv" -> "dataGeneratorGg9Runtime" to "com.gridgain.demo.datagen.cli.Gg9Main"
    else -> throw GradleException(
        "unsupported target kind '$kind' in $ops. Supported: gg8-kv, gg9-kv. " +
        "If this kind has been added to data-generator-core's TargetSpec, " +
        "DataGenerateTask must be extended to dispatch it and a matching " +
        "dataGenerator<Flavor>Runtime configuration must be declared."
    )
}

val cp = project.configurations.getByName(configurationName).files.toList()
// ... existing javaExe lookup ...
val outcome = action.execute(
    DataGenerateRequest(
        dataFile = data, opsFile = ops, scenarioName = scenarioName,
        clusterEndpointsFile = endpoints, outputDir = output,
        classpath = cp, mainClass = mainClass, javaExecutable = javaExe,
    )
)
```

- [ ] **Step 3: Update `GridGainDemoPlugin.kt`**

In the `apply()` block where the existing `dataGeneratorRuntime` configuration is auto-created (around line 327), replace the single configuration with a `listOf("dataGeneratorGg8Runtime", "dataGeneratorGg9Runtime").forEach { ... }` that creates each (with `isCanBeResolved = true`, `isCanBeConsumed = false`) only if absent. The legacy `dataGeneratorRuntime` is dropped — Task 9 updates TaxiDemo's reference accordingly.

- [ ] **Step 4: Update plugin `build.gradle.kts`**

Replace the existing `compileOnly("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")` and `dataGeneratorRuntime("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")` lines with the new triple. Note: the plugin's `dataGeneratorRuntime` declaration in `val dataGeneratorRuntime: Configuration by configurations.creating` is also replaced with the two new configurations:

```kotlin
val dataGeneratorGg8Runtime: Configuration by configurations.creating
val dataGeneratorGg9Runtime: Configuration by configurations.creating

dependencies {
    // ... unchanged ...

    // Data-generator: compile against core, runtime classpath chosen per scenario.
    compileOnly("com.gridgain.demo:gridgain-demo-data-generator-core:0.0.1-SNAPSHOT")
    dataGeneratorGg8Runtime("com.gridgain.demo:gridgain-demo-data-generator-gg8:0.0.1-SNAPSHOT")
    dataGeneratorGg9Runtime("com.gridgain.demo:gridgain-demo-data-generator-gg9:0.0.1-SNAPSHOT")
}
```

The two flavor configurations each transitively pull in the core jar (because of `api(project(":data-generator-core"))` in their build files), so no separate `dataGeneratorRuntime("...-core...")` line is needed.

- [ ] **Step 5: Build the plugin and run its existing test suite**

```bash
cd ../gridgain-demo-gradle-plugin
./gradlew test
```

Expected: green.

- [ ] **Step 6: Publish the plugin to maven-local**

```bash
./gradlew publishToMavenLocal
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(plugin): DataGenerateTask dispatches fork classpath + main class per target kind (Plan 7.5 Task 8)

Replaces the single dataGeneratorRuntime configuration with two:
dataGeneratorGg8Runtime and dataGeneratorGg9Runtime. Each carries the
matching flavor jar (gridgain-demo-data-generator-gg8 or -gg9) and its
unique ignite-* dep. DataGenerateTask reads ops.yaml via a cheap
YAMLMapper().readTree() walk to learn the named scenario's target's
kind, then picks the right configuration + main class
(Gg8Main or Gg9Main) for the fork.

Closes follow-up F8 (plugin per-target classpath dispatch).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Update TaxiDemo's `dataGeneratorRuntime` reference + smoke

**Files (in `TaxiDemo/`):**
- Modify: `build.gradle.kts` — replace the `dataGeneratorRuntime("...")` line.

TaxiDemo runs against `taxi-demo-gcp-8a` (a GG8 cluster), so we declare the GG8 flavor. A demo project that targets GG9 would declare `dataGeneratorGg9Runtime` instead — both are consumable in parallel.

- [ ] **Step 1: Replace the line**

Change:

```kotlin
"dataGeneratorRuntime"("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")
```

…to:

```kotlin
"dataGeneratorGg8Runtime"("com.gridgain.demo:gridgain-demo-data-generator-gg8:0.0.1-SNAPSHOT")
```

Demo projects that target GG9 would add `"dataGeneratorGg9Runtime"("...-gg9:0.0.1-SNAPSHOT")` instead (or in addition). Both configurations coexist on the consumer side; only one is resolved per `dataGenerate` invocation.

- [ ] **Step 2: Smoke against `taxi-demo-gcp-8a` (live cluster, GG8)**

```bash
cd TaxiDemo
./gradlew dataGenerate --scenario customer-load
```

Expected outcome (from Plan 8's smoke baseline): the run completes and `result.yaml` shows `success_count: 200` (or whatever the scenario's count duration declares), `error_count: 0` (or low), `achieved_rate` in the configured range, `stop_reason: count reached`. If smoke fails because no live cluster is currently up, document the reason in the report; the unit-level checks in Tasks 6 + 7 are still load-bearing.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(demo): point dataGenerator runtime at gg8 flavor jar (Plan 7.5 Task 9)

Replaces the legacy dataGeneratorRuntime declaration with
dataGeneratorGg8Runtime and the new artifactId
gridgain-demo-data-generator-gg8 (post-split). TaxiDemo targets a GG8
cluster (taxi-demo-gcp-8a); a demo against GG9 would declare
dataGeneratorGg9Runtime instead.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Update ROADMAP.md and write Plan 7.5 entry

**Files (in `gridgain-demo-data-generator/`):**
- Modify: `docs/superpowers/ROADMAP.md`
- Modify: `docs/superpowers/plans/2026-05-03-data-generator-plan-7-gg9-kv-target.md` — add a "superseded by 7.5" note at top.

- [ ] **Step 1: Update `ROADMAP.md`**

Three edits:

1. In the **Current State** block, bump the test count line if redistribution changed it (148 → ~148 still expected; only redistributed across modules). Add a sentence: "After Plan 7.5 the data generator is a multi-project build with three subprojects (`-core`, `-gg8`, `-gg9`); each per-version subproject carries one of `ignite-core` / `ignite-client` exclusively."

2. In **Open Follow-ups**, strike through (or move to a "Closed follow-ups" section) the F8 and F9 entries. Each should note "Closed in Plan 7.5 (subproject split)."

3. Add a new entry under the existing **Plan 8 — Plugin Invocation *(complete)*** + **Plan 7 — GG9 KV Target *(complete)*** section:

```
## Plan 7.5 — Subproject Split *(complete)*

The data generator is now a multi-project Gradle build with three
subprojects: `data-generator-core` (GG-agnostic), `data-generator-gg8`
(carries ignite-core 8.9.18), `data-generator-gg9` (carries ignite-client
9.1.3). The plugin's `DataGenerateTask` dispatches the fork classpath +
main class per resolved target kind. Closes F8 (plugin classpath split)
and F9 (reflection workaround in `Gg9KvTarget`).

All N tasks done — see `plans/2026-05-03-data-generator-subproject-split.md`.
```

Bump the "Last updated" line to `2026-05-03 (after Plan 7.5 subproject split landed)`.

- [ ] **Step 2: Add a header note to Plan 7's doc**

At the top of `2026-05-03-data-generator-plan-7-gg9-kv-target.md`, immediately under the title, add:

```
> **Note (2026-05-03):** Plan 7 used a reflection workaround in `Gg9KvTarget`
> (`gg9Tables()` / `gg9Transactions()`) to bypass the FQN collision between
> GG8's `ignite-core` and GG9's `ignite-api`. Plan 7.5 (subproject split) replaces
> that workaround structurally — see `2026-05-03-data-generator-subproject-split.md`.
> The reflection helpers are gone after Plan 7.5 Task 5.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/ROADMAP.md docs/superpowers/plans/2026-05-03-data-generator-plan-7-gg9-kv-target.md
git commit -m "$(cat <<'EOF'
docs(datagen): roadmap — Plan 7.5 complete; F8 + F9 closed (Plan 7.5 Task 10)

Records the subproject split as complete. Marks F8 (plugin per-target
classpath dispatch) and F9 (reflection workaround in Gg9KvTarget) as
closed. Adds a header note to Plan 7's doc pointing readers at the
structural fix.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Final cross-cutting code review

**Files:** verification only.

- [ ] **Step 1: Review checklist (manual)**

- Reflection helpers (`gg9Tables`, `gg9Transactions`) are gone from `data-generator-gg9/.../Gg9KvTarget.kt`.
- `Gg8KvTarget.kt`'s body is unchanged from pre-split (only location moved).
- `./gradlew clean test` from the data-generator root runs all 148 tests green.
- `ls ~/.m2/repository/com/gridgain/demo/ | grep gridgain-demo-data-generator` lists `-core`, `-gg8`, `-gg9` at `0.0.1-SNAPSHOT`.
- No remaining `dataGeneratorRuntime\b` reference in `gridgain-demo-gradle-plugin/build.gradle.kts` or its kotlin sources (only `dataGeneratorGg8Runtime` and `dataGeneratorGg9Runtime` survive).
- TaxiDemo's `build.gradle.kts` declares `dataGeneratorGg8Runtime`, not the legacy name.
- TaxiDemo smoke (Task 9 Step 2) succeeded if a live cluster was available; otherwise document in the report.

- [ ] **Step 2: Run `superpowers:requesting-code-review`**

A separate session-driven step that produces a structured review against the plan's goals. Invoke after Step 1 verifies clean.

---

## Self-review

Before declaring this plan complete:

- [ ] All `git mv` operations preserved history (verify with `git log --follow data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`).
- [ ] No file outside `Gg9KvTarget.kt` had its body modified — every other change is a move or an additive new file.
- [ ] `Gg8Main.kt` and `Gg9Main.kt` both carry `@file:JvmName("...")` so the plugin's launches resolve the classes.
- [ ] Every commit message ends with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- [ ] `data-generator-core/build.gradle.kts` exposes its consumed deps as `api` (not `implementation`) so `data-generator-gg8` and `data-generator-gg9` see jackson, datafaker, etc. on their compile classpath without re-declaring them.
- [ ] SnakeYAML is forced to 1.33 in the root `subprojects { configurations.all { resolutionStrategy { force(...) } } }` block — no per-subproject duplication.
- [ ] The `--add-opens` JVM args from Plan 6 stay on the GG8 module's `tasks.test` only.
- [ ] The plugin's `compileOnly("...-core...")` keeps `TargetSpec` and friends visible at compile time only — neither flavor jar is on the plugin's runtime classpath.
- [ ] The plugin's pre-parse logic (`resolveTargetKind`) errors with rich remediation messages, not silent fallbacks (per the project's `No fallbacks or backward compatibility` rule).
- [ ] No `org.gradle.*` import lives anywhere in `data-generator-core`'s source — that constraint applies because consumers include the plugin.
- [ ] After Task 6, the top-level `src/` directory is gone from disk entirely.

---

## Spec coverage audit

| Spec § | Covered by |
|--------|------------|
| Plan 7 follow-up F8 (plugin classpath split) | Task 8 |
| Plan 7 follow-up F9 (reflection workaround) | Task 5 |
| §3 GG8 KV target preserved | Task 4 (move only) |
| §3 GG9 KV target preserved | Task 5 (move + reflection-helper removal) |
| §3 capability flags preserved | Task 4 + Task 5 (no changes to fields) |
| §10 SnakeYAML 1.33 forced project-wide | Task 1 (root `subprojects` block) |
| §10 dependency minimization (no new deps) | All tasks (restructuring only) |
| Project rule: no Gradle imports in core | Task 2 + Task 3 (none added) |
| Project rule: rich error messages | Task 8 (`resolveTargetKind` errors), Tasks 4 + 5 (Main wrappers reject mismatched specs) |
| Project rule: do not collapse class hierarchies | Task 2 (`TargetSpec` sealed hierarchy + both subtypes preserved in core) |
| Project rule: `git mv` for source moves | Tasks 2, 4, 5 |

**Out of scope of this plan (deferred):**
- Plan 9 (Provisioning Emit + Apply) — unaffected by the split.
- Plan 10 (State Persistence) — unaffected.
- F1–F7 follow-ups — unaffected.
- Bumping the data-generator's version from `0.0.1-SNAPSHOT` to anything else; that decision belongs to the next release planning, not to a structural refactor.
- A "fat jar" or "uber jar" packaging that would re-bundle both flavors — the entire point of the split is that we never want both flavors co-resident.

---

## Critical files (forward references)

- `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCli.kt` — the GG-agnostic post-target orchestration. Plan 9 (provisioning) likely wraps this with a pre-step that emits/applies cache or table DDL before `runner.run()`.
- `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg8Main.kt` and the GG9 sibling — the per-flavor entry points the plugin launches.
- `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/plugin/tasks/DataGenerateTask.kt` — the dispatch site. If a future flavor is added (e.g. `gg10-kv`, or the deferred SQL targets §3), extend `resolveTargetKind`'s `when` and add a matching `dataGeneratorGg<N>Runtime` configuration in `GridGainDemoPlugin.kt`.
