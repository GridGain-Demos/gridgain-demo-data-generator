# Ops schema v7 — launch-time target selection (Phase 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use ggcoder:subagent-driven-development (recommended) or ggcoder:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `targets:` from `ops.yaml` so a scenario describes only a load shape, and take the target cluster from a new required `--target-cluster` CLI flag instead.

**Architecture:** Ops schema goes to v7 with `targets` and `scenario.target` deleted; a `MigrateOpsV6toV7` strips them and warns about what it discarded. `TargetSpec` stops being a deserialized config type and keeps its sealed hierarchy as a run-time value built by each `Main` from the cluster name. Because `Gg8Main`/`Gg9Main` already reject the wrong variant, the entry point determines the flavour and the schema's `kind` field carries no information.

**Tech Stack:** Kotlin 2.x, Gradle (Kotlin DSL), Jackson 2.17.2, SnakeYAML 1.33, networknt json-schema-validator 1.5.9, JUnit 5 + AssertJ + `kotlin.test`.

**Spec:** `../../../gridgain-demo-ui/docs/superpowers/specs/2026-08-22-scenarios-page-and-launch-time-target-selection-design.md`

**Phase position:** This lands first. Phase 2 (plugin dispatch) and Phase 3 (UI) both depend on the CLI flag and the published artifacts this produces. Nothing outside this repo works until Task 12 publishes.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `data-generator-core/src/main/resources/schema/ops/v7.schema.json` | Create | The v7 ops contract — v6 minus every target construct |
| `data-generator-core/src/main/kotlin/.../config/MigrateOpsV6toV7.kt` | Create | Strip `targets:` + `scenario.target`, warn naming what was discarded |
| `.../config/ConfiguredVersions.kt` | Modify | `CURRENT_OPS_SCHEMA_VERSION` 6 → 7 |
| `.../config/OpsConfigMigrationRunner.kt` | Modify | Register the new step |
| `.../config/OpsConfig.kt` | Modify | Drop `targets` (and its rule-exception note, now moot) |
| `.../config/ScenarioSpec.kt` | Modify | Drop `target` and its back-compat default |
| `.../config/CrossElementValidator.kt` | Modify | Delete `ScenarioTargetValidator` wholesale |
| `.../config/ConfigurationParser.kt` | Modify | Deregister that validator |
| `.../config/TargetSpec.kt` | Modify | Sealed hierarchy kept; Jackson annotations and `name` removed |
| `.../cli/CliArgs.kt` | Modify | New required `--target-cluster` |
| `.../cli/ScenarioRunnerCli.kt` | Modify | `Resolution.targetClusterName` replaces `targetSpec`; drop ops-side lookup |
| `data-generator-gg8/src/main/kotlin/.../cli/Gg8Main.kt` | Modify | Build `Gg8KvTargetSpec` from the flag; drop the cast-and-reject |
| `data-generator-gg9/src/main/kotlin/.../cli/Gg9Main.kt` | Modify | Same for gg9 |
| `.claude/skills/gridgain-demo-data-generator/SKILL.md` | Modify | ops.yaml, CLI and gotchas |
| `CLAUDE.md` | Modify | Key-files map if it names the deleted validator |

**Tests** (all under `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/`):

| File | Action |
|------|--------|
| `config/MigrateOpsV6toV7Test.kt` | Create |
| `config/JsonSchemaValidatorTest.kt` | Modify — v7 rejects target constructs |
| `config/OpsConfigMigrationRunnerTest.kt` | Modify — v6 → v7 end to end |
| `config/ConfiguredVersionsTest.kt` | Modify — the constant |
| `config/ScenarioTargetValidatorTest.kt` | **Delete** |
| `config/CrossElementValidatorTest.kt` | Modify — drop target-validator cases |
| `config/ConfigurationParserTest.kt` | Modify — fixtures lose `targets:` |
| `cli/CliArgsTest.kt` | Create or modify — the new flag |

### Why `ScenarioTargetValidator` is deleted rather than reworked

`CrossElementValidator.kt:234` — `capabilitiesFor` returns `true to true` for **both** `Gg8KvTargetSpec` and `Gg9KvTargetSpec`. So its two capability checks (`read_ratio` needs reads, `business_event` needs transactions) can never fire: they are dead branches. Everything else the validator does is resolving a target name that no longer exists. Nothing of value is lost.

This is *not* a hierarchy collapse — `TargetSpec`'s sealed hierarchy stays. Only a validator whose input is being removed goes.

---

## Task 1: The v7 ops schema

**Files:**
- Create: `data-generator-core/src/main/resources/schema/ops/v7.schema.json`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidatorTest.kt`

`JsonSchemaValidator.validateOps(yamlText, fileName, version)` already resolves `/schema/ops/v$version.schema.json`, so a new resource file is all the wiring needed.

- [ ] **Step 1: Write the failing tests**

Add to `JsonSchemaValidatorTest.kt`:

```kotlin
@Test
fun `v7 rejects a top-level targets block`() {
    val yaml = """
        schema_version: 7
        targets:
          - name: t1
            kind: gg8-kv
            cluster_name: c1
        scenarios:
          - name: s1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 10 }
            duration: { kind: count, value: 5 }
            read_ratio: 0.0
    """.trimIndent()

    assertThatThrownBy { JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7) }
        .isInstanceOf(MisconfigurationException::class.java)
        .hasMessageContaining("targets")
}

@Test
fun `v7 rejects a scenario target field`() {
    val yaml = """
        schema_version: 7
        scenarios:
          - name: s1
            target: t1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 10 }
            duration: { kind: count, value: 5 }
            read_ratio: 0.0
    """.trimIndent()

    assertThatThrownBy { JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7) }
        .isInstanceOf(MisconfigurationException::class.java)
        .hasMessageContaining("target")
}

@Test
fun `v7 accepts a scenario with no target and no targets block`() {
    val yaml = """
        schema_version: 7
        scenarios:
          - name: s1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 10 }
            duration: { kind: count, value: 5 }
            read_ratio: 0.0
    """.trimIndent()

    JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7)  // must not throw
}
```

Both rejections rely on `additionalProperties: false`, which v6 already sets at the top level **and** on `scenario`. Verify both survive the copy in Step 3 — without them these tests pass for the wrong reason.

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew :data-generator-core:test --tests "*JsonSchemaValidatorTest*"
```

Expected: FAIL — the v7 resource does not exist, so the message names a missing schema resource, not a validation error.

- [ ] **Step 3: Create the v7 schema**

```bash
cp data-generator-core/src/main/resources/schema/ops/v6.schema.json \
   data-generator-core/src/main/resources/schema/ops/v7.schema.json
```

Then edit `v7.schema.json` and delete exactly these five things:

1. `properties.targets` (the whole key)
2. `$defs.target`
3. `$defs.target_gg8_kv`
4. `$defs.target_gg9_kv`
5. `$defs.scenario.properties.target`

Leave alone: `additionalProperties: false` at both levels; `required` at top level (`["schema_version", "scenarios"]` — `targets` was never in it); `$defs.scenario.required` (`target` was never in it either).

Update the schema's `title`/`description` to say v7 if v6 states its version there.

- [ ] **Step 4: Run to verify they pass**

```bash
./gradlew :data-generator-core:test --tests "*JsonSchemaValidatorTest*"
```

Expected: PASS, all three.

- [ ] **Step 5: Commit**

```bash
git add data-generator-core/src/main/resources/schema/ops/v7.schema.json \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidatorTest.kt
git commit -m "feat(ops): add v7 schema with every target construct removed"
```

---

## Task 2: `MigrateOpsV6toV7`

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV6toV7.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV6toV7Test.kt`

`ConfigMigration.migrate(yaml)` takes **no logger**, and the runner's logger is not passed down. Rather than change the interface for all seven existing migrations — six of which have nothing to report — inject one here with a constructor default. That is dependency injection, not a configuration default, so the comprehensive-config policy does not apply.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.DataGenLogger
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV6toV7Test {

    /** Captures warnings so the discarded-mapping report can be asserted on. */
    private class RecordingLogger : DataGenLogger {
        val warnings = mutableListOf<String>()
        override fun lifecycle(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) { warnings += message }
        override fun error(message: String, throwable: Throwable?) = Unit
        override fun debug(message: String) = Unit
    }

    @Test
    fun `from and to versions are 6 and 7`() {
        val m = MigrateOpsV6toV7()
        assertThat(m.fromVersion).isEqualTo(6)
        assertThat(m.toVersion).isEqualTo(7)
        assertThat(m.description).contains("target")
    }

    @Test
    fun `removes the top-level targets block`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf(
                mutableMapOf("name" to "t1", "kind" to "gg8-kv", "cluster_name" to "c1")
            ),
            "scenarios" to mutableListOf<Any>(),
        )
        val out = MigrateOpsV6toV7().migrate(map)
        assertThat(out).doesNotContainKey("targets")
    }

    @Test
    fun `removes target from every scenario`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "scenarios" to mutableListOf(
                mutableMapOf("name" to "s1", "target" to "t1"),
                mutableMapOf("name" to "s2", "target" to "t2"),
            ),
        )
        val out = MigrateOpsV6toV7().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val scenarios = out["scenarios"] as List<Map<String, Any>>
        assertThat(scenarios).allSatisfy { assertThat(it).doesNotContainKey("target") }
        assertThat(scenarios.map { it["name"] }).containsExactly("s1", "s2")
    }

    @Test
    fun `warns naming each discarded scenario-to-cluster mapping`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf(
                mutableMapOf("name" to "t1", "kind" to "gg8-kv", "cluster_name" to "prod-gg8")
            ),
            "scenarios" to mutableListOf(
                mutableMapOf("name" to "load", "target" to "t1")
            ),
        )
        MigrateOpsV6toV7(logger).migrate(map)

        assertThat(logger.warnings).hasSize(1)
        assertThat(logger.warnings.single())
            .contains("load")
            .contains("prod-gg8")
            .contains("--target-cluster")
    }

    @Test
    fun `is silent and idempotent on a file that never had targets`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "scenarios" to mutableListOf(mutableMapOf("name" to "s1")),
        )
        val out = MigrateOpsV6toV7(logger).migrate(map)

        assertThat(out).doesNotContainKey("targets")
        assertThat(logger.warnings).isEmpty()
    }

    @Test
    fun `reports a scenario whose target does not resolve`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf<Any>(),
            "scenarios" to mutableListOf(mutableMapOf("name" to "s1", "target" to "ghost")),
        )
        MigrateOpsV6toV7(logger).migrate(map)

        assertThat(logger.warnings.single()).contains("s1").contains("ghost")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :data-generator-core:test --tests "*MigrateOpsV6toV7Test*"
```

Expected: FAIL — `MigrateOpsV6toV7` is unresolved, so this is a compilation failure.

- [ ] **Step 3: Write the migration**

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.slf4j.LoggerFactory

/**
 * v6 -> v7: `targets:` and every `scenario.target` are removed. A scenario describes a load shape;
 * the cluster it runs against is chosen at launch (`--target-cluster`), so it is no longer written
 * into the file at all.
 *
 * This is the first **lossy** migration: the scenario-to-cluster wiring is real information and
 * nothing in the v7 file records it. So each discarded mapping is reported at WARN, naming the
 * scenario, the cluster it used to name, and the flag that now supplies it — an operator who reads
 * the log can reconstruct their launch arguments. Silently dropping it would make this a data loss
 * dressed as an upgrade.
 *
 * The logger is injected (not taken from [ConfigMigrationRunner], whose logger is not passed into
 * `migrate`) so the report is assertable. That is dependency injection, not a configuration
 * default, so the comprehensive-configuration-file policy does not apply to it.
 */
class MigrateOpsV6toV7(
    private val logger: DataGenLogger =
        Slf4jDataGenLogger(LoggerFactory.getLogger(MigrateOpsV6toV7::class.java)),
) : ConfigMigration {

    override val fromVersion: Int = 6
    override val toVersion: Int = 7
    override val description: String =
        "remove targets and scenario.target; the target cluster is now chosen at launch"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        val clusterByTargetName = readTargetClusters(yaml)
        yaml.remove("targets")

        @Suppress("UNCHECKED_CAST")
        val scenarios = yaml["scenarios"] as? MutableList<Any> ?: return yaml
        for (element in scenarios) {
            @Suppress("UNCHECKED_CAST")
            val scenario = element as? MutableMap<String, Any> ?: continue
            val targetName = scenario.remove("target") as? String ?: continue
            report(scenario["name"] as? String ?: "(unnamed)", targetName, clusterByTargetName)
        }
        return yaml
    }

    private fun readTargetClusters(yaml: Map<String, Any>): Map<String, String> {
        val targets = yaml["targets"] as? List<*> ?: return emptyMap()
        return targets.mapNotNull { element ->
            val target = element as? Map<*, *> ?: return@mapNotNull null
            val name = target["name"] as? String ?: return@mapNotNull null
            val cluster = target["cluster_name"] as? String ?: return@mapNotNull null
            name to cluster
        }.toMap()
    }

    private fun report(scenario: String, targetName: String, clusters: Map<String, String>) {
        val cluster = clusters[targetName]
        logger.warn(
            if (cluster != null) {
                "ops migration v6 -> v7 discarded the target of scenario '$scenario': it named " +
                    "target '$targetName' on cluster '$cluster'. The cluster is no longer stored in " +
                    "ops.yaml — pass '--target-cluster $cluster' when running this scenario, or pick " +
                    "the cluster on the demo UI's Load page."
            } else {
                "ops migration v6 -> v7 discarded the target of scenario '$scenario': it named " +
                    "target '$targetName', which was not declared in targets[], so no cluster name " +
                    "could be recovered from it. Supply the cluster with '--target-cluster <name>' " +
                    "when running this scenario."
            }
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :data-generator-core:test --tests "*MigrateOpsV6toV7Test*"
```

Expected: PASS, all six.

- [ ] **Step 5: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV6toV7.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/MigrateOpsV6toV7Test.kt
git commit -m "feat(ops): add v6 to v7 migration reporting each discarded target"
```

---

## Task 3: Register the migration and bump the current version

**Files:**
- Modify: `.../config/OpsConfigMigrationRunner.kt`
- Modify: `.../config/ConfiguredVersions.kt:4`
- Test: `.../config/OpsConfigMigrationRunnerTest.kt`, `.../config/ConfiguredVersionsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `OpsConfigMigrationRunnerTest.kt` — match the file's existing style for building a temp config file:

```kotlin
@Test
fun `migrates a v6 file with targets all the way to v7`(@TempDir dir: Path) {
    val file = dir.resolve("ops.yaml").toFile()
    file.writeText(
        """
        schema_version: 6
        targets:
          - name: t1
            kind: gg8-kv
            cluster_name: prod-gg8
        scenarios:
          - name: load
            target: t1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 10 }
            duration: { kind: count, value: 5 }
            read_ratio: 0.0
        """.trimIndent()
    )

    OpsConfigMigrationRunner.create()
        .ensureCurrentVersion(file, CURRENT_OPS_SCHEMA_VERSION, RecordingLogger())

    val rewritten = file.readText()
    assertThat(rewritten).contains("schema_version: 7")
    assertThat(rewritten).doesNotContain("targets")
    assertThat(rewritten).doesNotContain("target:")
}
```

And in `ConfiguredVersionsTest.kt`, update the ops assertion to `7`.

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew :data-generator-core:test --tests "*OpsConfigMigrationRunnerTest*" --tests "*ConfiguredVersionsTest*"
```

Expected: FAIL — no migration registered from 6, so `ensureCurrentVersion` throws "No migration path from schema_version 6 to 7"; and the version constant is still 6.

- [ ] **Step 3: Register and bump**

In `OpsConfigMigrationRunner.kt`, add `MigrateOpsV6toV7(),` after `MigrateOpsV5toV6(),`.

In `ConfiguredVersions.kt`, change `CURRENT_OPS_SCHEMA_VERSION` from `6` to `7`.

- [ ] **Step 4: Run to verify they pass**

```bash
./gradlew :data-generator-core:test --tests "*OpsConfigMigrationRunnerTest*" --tests "*ConfiguredVersionsTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfigMigrationRunner.kt \
        data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/OpsConfigMigrationRunnerTest.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersionsTest.kt
git commit -m "feat(ops): make v7 the current ops schema version"
```

---

## Task 4: Drop `targets` and `scenario.target` from the config classes

**Files:**
- Modify: `.../config/OpsConfig.kt`
- Modify: `.../config/ScenarioSpec.kt:23`

Expect a broad compile break — every test fixture constructing `OpsConfig(targets = ...)` or `ScenarioSpec(target = ...)` fails. That is the point: the compiler enumerates the call sites.

- [ ] **Step 1: Delete the fields**

In `OpsConfig.kt`, remove `val targets: List<TargetSpec> = emptyList(),` **and** the whole KDoc note above the class explaining the `emptyList()` rule exception — it documents a field that no longer exists.

In `ScenarioSpec.kt`, remove `val target: String = "",` and its trailing comment. Leave the class KDoc's notes on `transactionScope` and `distribution` alone; they are still accurate.

- [ ] **Step 2: Compile to enumerate the breaks**

```bash
./gradlew :data-generator-core:compileTestKotlin
```

Expected: FAIL, listing every fixture that names `targets =` or `target =`. Record the list — it is the work for Step 3.

- [ ] **Step 3: Fix every call site**

Delete the `targets = ...` and `target = ...` arguments. Do not replace them with anything. `ConfigurationParserTest.kt` and `CrossElementValidatorTest.kt` are the likeliest holders of YAML fixtures with a `targets:` block — those need the block removed from the YAML text too, and their `schema_version` raised to 7.

- [ ] **Step 4: Compile and test**

```bash
./gradlew :data-generator-core:build
```

Expected: `ScenarioTargetValidator` still fails to compile — it reads `ops.targets` and `scenario.target`. That is Task 5. If nothing else fails, proceed.

- [ ] **Step 5: Commit** (with Task 5, since the tree does not compile in between)

---

## Task 5: Delete `ScenarioTargetValidator`

**Files:**
- Modify: `.../config/CrossElementValidator.kt:202-238`
- Modify: `.../config/ConfigurationParser.kt:24`
- Delete: `.../config/ScenarioTargetValidatorTest.kt`

- [ ] **Step 1: Delete the class**

Remove `class ScenarioTargetValidator` in its entirety from `CrossElementValidator.kt`, including its private `capabilitiesFor` helper. Both capability branches return `true to true`, so nothing that could ever have fired is being removed — see the rationale in File Structure above.

- [ ] **Step 2: Deregister it**

Remove `ScenarioTargetValidator(),` from the validator list in `ConfigurationParser.kt:24`.

- [ ] **Step 3: Delete its test**

```bash
git rm data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ScenarioTargetValidatorTest.kt
```

- [ ] **Step 4: Build**

```bash
./gradlew :data-generator-core:build
```

Expected: PASS. If `CrossElementValidatorTest.kt` still references the deleted class, remove those cases too.

- [ ] **Step 5: Commit**

```bash
git add -A data-generator-core
git commit -m "refactor(ops)!: remove targets from OpsConfig and ScenarioSpec

Deletes ScenarioTargetValidator with them. Its two capability checks were
dead branches — capabilitiesFor returned true to true for both target kinds —
so the validator only ever resolved a target name that no longer exists."
```

---

## Task 6: `TargetSpec` becomes a run-time value

**Files:**
- Modify: `.../config/TargetSpec.kt`
- Delete or rewrite: `.../config/Gg9KvTargetSpecDeserializationTest.kt`

The sealed hierarchy **stays** — it is intentional polymorphism and the no-collapsing rule applies. What goes is its role as a deserialized config type.

- [ ] **Step 1: Rewrite the file**

```kotlin
package com.gridgain.demo.datagen.config

/**
 * The cluster a run writes to, in the flavour of the entry point that built it.
 *
 * No longer deserialized from ops.yaml: v7 removed `targets:`, and the cluster now arrives on the
 * CLI as `--target-cluster`. Each `Main` constructs the one variant it can serve — `Gg8Main` a
 * [Gg8KvTargetSpec], `Gg9Main` a [Gg9KvTargetSpec] — which is why the old `kind` discriminator
 * carried no information the entry point did not already have.
 *
 * The sealed hierarchy is retained deliberately: it is how a flavour-specific target is passed
 * without a cast, and further kinds are expected.
 */
sealed class TargetSpec {
    abstract val clusterName: String
}

data class Gg8KvTargetSpec(override val clusterName: String) : TargetSpec()

data class Gg9KvTargetSpec(override val clusterName: String) : TargetSpec()
```

Note what is gone: `@JsonTypeInfo`, `@JsonSubTypes`, `@JsonProperty`, the Jackson imports, and `name`. `name` had no source once `targets:` went, and its only readers were error messages and the `target` telemetry attribute (Task 10).

- [ ] **Step 2: Handle the deserialization test**

`Gg9KvTargetSpecDeserializationTest.kt` tests a capability that no longer exists. Delete it:

```bash
git rm data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/Gg9KvTargetSpecDeserializationTest.kt
```

- [ ] **Step 3: Compile**

```bash
./gradlew :data-generator-core:compileTestKotlin
```

Expected: FAIL in `ScenarioRunnerCli.kt` (still looks targets up in ops) — that is Task 7/8.

- [ ] **Step 4: Commit** (with Task 8, since the tree does not compile in between)

---

## Task 7: `--target-cluster` on the CLI

**Files:**
- Modify: `.../cli/CliArgs.kt`
- Test: `.../cli/CliArgsTest.kt` (create if absent)

`CliArgs.kt` already has the good pattern: a `REQUIRED_FLAGS` list, and a `required()` helper whose failure names the missing flag and the full expected invocation. Adding to the list gets all of that for free — which is exactly what the "missing argument reports a raw stacktrace" gotcha needed.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class CliArgsTest {

    private val complete = arrayOf(
        "--data", "/tmp/data.yaml",
        "--ops", "/tmp/ops.yaml",
        "--scenario", "load",
        "--cluster-endpoints", "/tmp/client-endpoints.yaml",
        "--output", "/tmp/out",
        "--run-group", "rg-1",
        "--target-cluster", "prod-gg8",
    )

    @Test
    fun `parses the target cluster`() {
        assertThat(parseArgs(complete).targetCluster).isEqualTo("prod-gg8")
    }

    @Test
    fun `a missing target cluster names the flag and the full invocation`() {
        val without = complete.toList().dropLast(2).toTypedArray()

        assertThatThrownBy { parseArgs(without) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
            .hasMessageContaining("Expected invocation")
    }

    @Test
    fun `a blank target cluster is rejected like a missing one`() {
        val blank = complete.copyOf()
        blank[blank.lastIndex] = "  "

        assertThatThrownBy { parseArgs(blank) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :data-generator-core:test --tests "*CliArgsTest*"
```

Expected: FAIL — `targetCluster` is not a member of `CliArgs`.

- [ ] **Step 3: Add the flag**

In `CliArgs.kt`, add the field with a KDoc:

```kotlin
    /**
     * The cluster this run writes to, resolved against `client-endpoints.yaml`. Supplied per run
     * rather than per file since ops v7: a scenario describes a load shape, and the same shape is
     * run against different clusters. Required — a run with no cluster has nothing to write to, and
     * defaulting it would silently load the wrong grid.
     */
    val targetCluster: String,
```

Place it before `otelEndpointOverride` (required fields first). Add `"--target-cluster"` to the end of `REQUIRED_FLAGS`, and in the `CliArgs(...)` construction add:

```kotlin
        targetCluster = required(map, "--target-cluster"),
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :data-generator-core:test --tests "*CliArgsTest*"
```

Expected: PASS, all three. The blank case passes because `required()` already tests `isNullOrBlank`.

- [ ] **Step 5: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/CliArgs.kt \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/cli/CliArgsTest.kt
git commit -m "feat(cli)!: require --target-cluster"
```

---

## Task 8: `Resolution` carries the cluster name, not a `TargetSpec`

**Files:**
- Modify: `.../cli/ScenarioRunnerCli.kt:47` (the `Resolution` field) and `:85` (the ops-side lookup)
- Modify: `.../cli/ScenarioRunnerCli.kt:~302,~309` (lifecycle events reading `targetSpec.name`)

Core cannot build a `Gg8KvTargetSpec` or a `Gg9KvTargetSpec` — it does not know which. Only the `Main` does. So `Resolution` carries the raw name and each `Main` builds its own variant, which is what deletes the cast-and-reject from both.

- [ ] **Step 1: Replace the field**

In `Resolution`, replace `val targetSpec: TargetSpec,` with:

```kotlin
        /**
         * The cluster named by `--target-cluster`. Deliberately a name, not a [TargetSpec]: core
         * cannot know which flavour to build, and each `Main` builds the only one it can serve.
         */
        val targetClusterName: String,
```

- [ ] **Step 2: Delete the ops-side lookup**

Remove the whole `val targetSpec = parsedConfig.ops.targets.firstOrNull { ... } ?: throw ...` block (around line 85) and pass `targetClusterName = parsed.targetCluster` in the `Resolution(...)` construction instead of `targetSpec = targetSpec`.

- [ ] **Step 3: Fix the lifecycle events**

At the two sites reading `resolution.targetSpec.name` (~302, ~309), use `resolution.targetClusterName`. The attribute keeps its name (`target`) and changes what it holds — see Task 10.

- [ ] **Step 4: Update both `Main`s**

In `Gg8Main.kt`, delete the cast-and-reject block entirely and replace it with:

```kotlin
        // The only variant this entry point can serve. Before ops v7 this was a cast of a
        // deserialized target plus an error for the wrong kind; the kind was never information the
        // entry point lacked.
        val spec = Gg8KvTargetSpec(resolution.targetClusterName)
```

The `MisconfigurationException` import may become unused — remove it if so. Apply the mirror change in `Gg9Main.kt` with `Gg9KvTargetSpec`.

- [ ] **Step 5: Build the whole project**

```bash
./gradlew build
```

Expected: PASS across `core`, `gg8` and `gg9`. Fix any remaining fixture that constructs a `Resolution`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(cli)!: take the target cluster from the CLI, not ops.yaml

Resolution carries the cluster name; each Main builds the one TargetSpec
variant it can serve, which removes the cast-and-reject both carried."
```

---

## Task 9: End-to-end parse of a v7 file

**Files:**
- Test: `.../config/ConfigurationParserTest.kt`

Tasks 1–8 each proved a piece. This proves a v6 file on disk becomes a running configuration.

- [ ] **Step 1: Write the test**

```kotlin
@Test
fun `a v6 ops file with targets migrates, validates and parses as v7`(@TempDir dir: Path) {
    val ops = dir.resolve("ops.yaml").toFile()
    ops.writeText(
        """
        schema_version: 6
        targets:
          - name: t1
            kind: gg8-kv
            cluster_name: prod-gg8
        scenarios:
          - name: load
            target: t1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 10 }
            duration: { kind: count, value: 5 }
            read_ratio: 0.0
        """.trimIndent()
    )
    val data = dir.resolve("data.yaml").toFile()
    data.writeText(/* minimal valid data.yaml — copy the fixture this test class already uses */)

    val parsed = ConfigurationParser(logger = RecordingLogger()).parse(data, ops)

    assertThat(parsed.ops.schemaVersion).isEqualTo(7)
    assertThat(parsed.ops.scenarios.single().name).isEqualTo("load")
}
```

- [ ] **Step 2: Run**

```bash
./gradlew :data-generator-core:test --tests "*ConfigurationParserTest*"
```

Expected: PASS. A failure here means migration and validation disagree — most likely a target construct left in the v7 schema (re-check Task 1 Step 3).

- [ ] **Step 3: Commit**

```bash
git add data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt
git commit -m "test(ops): cover v6 to v7 migrate, validate and parse end to end"
```

---

## Task 10: Confirm what the `target` telemetry attribute now carries

**Files:**
- Review: `.../observability/Instruments.kt:37,81`
- Review: `.../observability/LifecycleEvent.kt:20`

No code change is expected. `ATTR_TARGET` stays `"target"`; its **value** changes from a target alias to a cluster name. This task exists so that change is deliberate and recorded rather than discovered in a dashboard.

- [ ] **Step 1: Confirm the attribute name is unchanged**

```bash
grep -rn "ATTR_TARGET\|\"target\"" data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/
```

Expected: the constant is still `"target"`. Renaming it would break every existing dashboard query and is explicitly not wanted.

- [ ] **Step 2: Confirm the value now flows from the cluster name**

```bash
grep -rn "targetClusterName\|targetName" data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/
```

Expected: every producer of a `target` attribute traces back to `Resolution.targetClusterName`.

- [ ] **Step 3: Record it where it will be found**

Add to the KDoc on `ATTR_TARGET`:

```kotlin
        /**
         * The target cluster. Since ops v7 this carries the **cluster name** — before v7 it carried
         * the alias of a `targets[]` entry. The attribute name is unchanged so existing queries keep
         * resolving; a dashboard that groups by it will re-label rather than empty.
         */
```

- [ ] **Step 4: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/Instruments.kt
git commit -m "docs(observability): record that the target attribute now holds a cluster name"
```

---

## Task 11: Update the usage skill and repo docs

**Files:**
- Modify: `.claude/skills/gridgain-demo-data-generator/SKILL.md`
- Modify: `CLAUDE.md` (only if it names `ScenarioTargetValidator` or `TargetSpec` in its key-files map)

Both this repo's `CLAUDE.md` and the skill's own Maintenance section require this in the same change as the surface it documents.

- [ ] **Step 1: Edit the skill**

1. **Header table** — ops.yaml `Current schema_version` 6 → **7**.
2. **§ops.yaml** — delete the `targets:` block from the example and the `target: my-cluster` line from the scenario; delete the `# referenced by scenario.target` comment.
3. **§CLI** — add `--target-cluster <name>` to the required flags, explaining that the cluster is per run, not per file.
4. **§Gotchas** — replace gotcha 7 (the v6/archive pairing) with the v7 equivalent and add two:
   - An archive built before v7 refuses a v7 file, and a v7 archive requires `--target-cluster`; upgrading the ops file therefore means redeploying every generator archive.
   - The `target` telemetry attribute now carries a cluster name, so dashboards grouping by it re-label.
5. **§Sources of truth** — remove `ScenarioTargetValidator`; keep `TargetSpec.kt` but note it is no longer deserialized.
6. **Bump *Last updated* to 2026-08-22.**

- [ ] **Step 2: Check `CLAUDE.md`**

```bash
grep -n "ScenarioTargetValidator\|TargetSpec\|targets" CLAUDE.md
```

Fix any stale reference. No change if it names neither.

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/gridgain-demo-data-generator/SKILL.md CLAUDE.md
git commit -m "docs(skill): ops v7 removes targets; --target-cluster is required"
```

---

## Task 12: Publish and rebuild the delivery artifacts

Phases 2 and 3 resolve these from `mavenLocal`, and every deployed generator is one of these archives or images. Until this task runs, nothing downstream can move.

- [ ] **Step 1: Full build**

```bash
./gradlew build
```

Expected: PASS. Do not proceed on a red build.

- [ ] **Step 2: Publish to mavenLocal**

```bash
./gradlew publishToMavenLocal
```

Expected: `gridgain-demo-data-generator-core`, `-gg8`, `-gg9` at `0.0.1-SNAPSHOT` in `~/.m2/repository/com/gridgain/demo/`.

- [ ] **Step 3: Rebuild the host archives**

```bash
./gradlew buildStandardDistributions
```

Expected: `data-generator-gg{8,9}-dist/build/distributions/*.tar.gz`. These must be **gzipped** — `distTar` produces an uncompressed `.tar` unless GZIP is configured, and `ArchiveFormat` rejects it on the machine after a clean build.

- [ ] **Step 4: Rebuild the images**

```bash
./gradlew buildDataGeneratorImages
```

Only needed for the k8s modes. If the images use a mutable tag, note it for Phase 2: a running pod may cache the old jar and run it against a v7 ops file.

- [ ] **Step 5: Verify the flag reaches a real run**

```bash
./gradlew :data-generator-gg8:run --args="--data /tmp/nope.yaml"
```

Expected: a `MisconfigurationException` naming `--target-cluster` among the missing flags and printing the full expected invocation — **not** a `NoSuchElementException` stacktrace. This is the gotcha this phase was meant to close; confirm it directly.

- [ ] **Step 6: Commit anything the build changed**

```bash
git status --short
```

Commit only source changes. Do not commit `build/` output.

---

## Done when

- [ ] `./gradlew build` passes.
- [ ] A v6 ops file with `targets:` migrates, validates and parses as v7, warning once per discarded scenario.
- [ ] The v7 schema rejects both a `targets:` block and a `scenario.target`.
- [ ] A run with no `--target-cluster` fails with a message naming the flag.
- [ ] `ScenarioTargetValidator` and its test are gone; `TargetSpec`'s sealed hierarchy is not.
- [ ] Artifacts, host archives and images are rebuilt and published.
- [ ] The skill is updated and *Last updated* is 2026-08-22.

## Handing off to Phase 2

Phase 2 (`gridgain-demo-gradle-plugin`) needs from here:

- The flag is spelled **`--target-cluster`** (generator CLI), which the plugin will expose as **`--targetCluster`** (Gradle option). Two spellings, deliberately — the toolkit skill's "two different spellings" warning becomes three.
- `--target-cluster` is required in **every** mode, including the host systemd unit, whose `ExecStart` is rendered at deploy time. That unit needs the per-run env-file mechanism the spec describes; it is Phase 2's first real task, not an afterthought.
- The generator no longer reads a cluster from `ops.yaml`, so the plugin's `TargetResolution` must stop reading one from there too.
