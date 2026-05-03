# Data Generator — Plan 8: Plugin Invocation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the data generator runnable from inside `gridgain-demo-gradle-plugin` so demo flows look like `cd TaxiDemo && ./gradlew dataGenerate --scenario <name>`. The plugin already deploys the cluster and writes `client-endpoints.yaml`; this plan wires the data generator as a downstream action that reuses the plugin's context (config file path, output directory, cluster endpoints) without the user touching env vars.

**Architecture:** Add `maven-publish` to the data-generator and publish to maven local. The plugin pulls in `com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT` and exposes a `dataGenerate` task. **Critical classpath note:** the plugin currently depends on `org.gridgain:ignite-core:9.1.3` (GG9), and the data-generator pulls in `org.gridgain:ignite-core:8.9.18` (GG8) for `Gg8KvTarget`. Same artifact, different major versions, same packages — they will conflict on a single classpath. Plan 8 resolves this by running the data generator in a forked JVM with its own classpath (same approach `gg8-client-finder`'s `TestClientV8` documents in its build.gradle.kts comment). The plugin task assembles the fork's classpath from the data-generator's published artifacts + ignite-core 8.9.18, runs `java -cp ... com.gridgain.demo.datagen.cli.Main <args>`, and reports the exit code + parsed result.yaml back into the plugin's run log.

**Tech Stack additions on the data-generator side:**
- `maven-publish` Gradle plugin
- A small CLI entry point (`com.gridgain.demo.datagen.cli.Main`) with arg parsing for `--data <path>`, `--ops <path>`, `--scenario <name>`, `--cluster-endpoints <path>`, `--output <demoOutputDirectory>`

**Tech Stack additions on the plugin side:**
- `implementation("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")` (compileOnly is also viable since the plugin doesn't directly call data-generator types — it just shells out to the forked JVM)
- A new core action class `DataGenerateAction` and a Gradle task wrapper `DataGenerateTask`
- Task registration in `GridGainDemoPlugin`

**Pre-execution prerequisites (operator must satisfy before Task 1):**
1. Plans 1–6 are complete and the data generator's standalone test suite is green.
2. `gridgain-demo-client-utils` is published to maven local (already verified during Plan 6).
3. The plugin builds and tests cleanly today.

---

## Plugin invocation shape (target user experience)

From inside TaxiDemo (its own gradle project — there is no top-level build that aggregates the siblings):
```
cd TaxiDemo
./gradlew dataGenerate --scenario customer-load
```

The plugin task:
1. Looks up `demoConfigFile` and `demoOutputDirectory` from `gradle.properties` (existing pattern).
2. Resolves data.yaml + ops.yaml paths (defaults: `<demoConfigFile>'s sibling data.yaml + ops.yaml`, overridable via `-PdataConfig=… -PopsConfig=…`).
3. Locates `client-endpoints.yaml` at `<demoOutputDirectory>/client/client-endpoints.yaml` (per the plugin's existing convention).
4. Forks a JVM with the data-generator's classpath, passes the paths + scenario name as args.
5. Captures stdout/stderr, parses the resulting `runs/<run-id>/result.yaml`, surfaces it in the plugin's run log.

---

## File Structure

```
gridgain-demo-data-generator/
├── build.gradle.kts                                                    (modify — add maven-publish)
└── src/main/kotlin/com/gridgain/demo/datagen/
    └── cli/
        └── Main.kt                                                     (NEW — CLI entry point)

gridgain-demo-gradle-plugin/
├── build.gradle.kts                                                    (modify — add data-generator dep)
└── src/main/kotlin/com/gridgain/demo/
    ├── core/                                                           (build-tool-agnostic)
    │   └── datagen/
    │       └── DataGenerateAction.kt                                  (NEW)
    └── plugin/tasks/
        └── DataGenerateTask.kt                                        (NEW)
```

---

### Task 1: Add `maven-publish` to data-generator + publish locally

**Files:** Modify `gridgain-demo-data-generator/build.gradle.kts`.

Add the `maven-publish` plugin and a publication producing `com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT`.

- [ ] **Step 1: Modify build.gradle.kts**

Append at the top of `plugins { ... }`:
```kotlin
`maven-publish`
```

After the existing top-level blocks, add:
```kotlin
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
```

- [ ] **Step 2: Verify publish to local maven**

```bash
cd gridgain-demo-data-generator
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/gridgain/demo/gridgain-demo-data-generator/0.0.1-SNAPSHOT/
```

Expected: directory contains `gridgain-demo-data-generator-0.0.1-SNAPSHOT.jar`, `.pom`, `.module`.

- [ ] **Step 3: Commit**

```bash
git add gridgain-demo-data-generator/build.gradle.kts
git commit -m "chore(datagen): publish to maven local"
```

Sign with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

### Task 2: CLI entry point in the data generator

**Files:** Create `gridgain-demo-data-generator/src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt`.

Small `main(args)` that parses `--data`, `--ops`, `--scenario`, `--cluster-endpoints`, `--output`. Constructs the runtime, runs the named scenario, exits 0 on success or 1 on error.

- [ ] **Step 1: Write Main.kt**

```kotlin
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.runtime.RunId
import com.gridgain.demo.datagen.scenario.ScenarioResult
import com.gridgain.demo.datagen.scenario.ScenarioRunner
import com.gridgain.demo.datagen.target.Gg8KvTarget
import com.gridgain.demo.datagen.target.Target
import net.datafaker.Faker
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("datagen-cli"))
    try {
        // Set the system property so DemoAddressFinder can locate client-endpoints.yaml.
        System.setProperty("gg.demo.client.endpoints", parsed.clusterEndpoints.toAbsolutePath().toString())

        val parser = ConfigurationParser(logger = logger)
        val parsedConfig = parser.parse(parsed.dataFile.toFile(), parsed.opsFile.toFile())

        val scenario = parsedConfig.ops.scenarios.firstOrNull { it.name == parsed.scenarioName }
            ?: throw MisconfigurationException(
                "Scenario '${parsed.scenarioName}' not declared in ops.yaml. " +
                "Available: ${parsedConfig.ops.scenarios.joinToString(", ") { it.name }}."
            )

        val targetSpec = parsedConfig.ops.targets.firstOrNull { it.name == scenario.target }
            ?: throw MisconfigurationException(
                "Target '${scenario.target}' referenced by scenario '${scenario.name}' " +
                "is not declared in ops.yaml's targets[]."
            )

        val keyColumnByName = parsedConfig.data.schemas.associate { schema ->
            schema.name to (schema.columns.first { it.key }.name)
        }

        val target: Target = when (targetSpec) {
            is Gg8KvTargetSpec -> Gg8KvTarget(
                clusterName = targetSpec.clusterName,
                keyColumnByName = keyColumnByName,
                transactionScope = scenario.transactionScope,
            )
        }

        val factory = ValueSourceFactory(yamlDataRoot = parsed.dataFile.parent, seed = 0L)
        val rootSchema = scenario.rootSchemas.first()
        val gen = BusinessEventGenerator(
            data = parsedConfig.data,
            rootSchemaName = rootSchema,
            factory = factory,
            faker = Faker(),
            cohortSeed = 0L,
        )

        val runner = ScenarioRunner(
            scenario = scenario,
            data = parsedConfig.data,
            generator = gen,
            target = target,
        )

        val result = runner.run()
        if (target is AutoCloseable) target.close()

        // Write result.yaml
        val layout = OutputLayout(parsed.outputDir)
        val runId = RunId.generate()
        layout.ensureBaseDirectories()
        val resultFile = layout.resultFile(runId)
        ScenarioResult.write(result, resultFile)

        logger.lifecycle("scenario '${scenario.name}' complete: ${result.successCount} successes, ${result.errorCount} errors, achieved_rate=${result.achievedRate}, stop_reason=${result.stopReason}")
        logger.lifecycle("result written to $resultFile")
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator failed: ${e.message}", e)
        exitProcess(1)
    }
}

private data class CliArgs(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpoints: Path,
    val outputDir: Path,
)

private fun parseArgs(args: Array<String>): CliArgs {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(i + 1 < args.size) { "argument $key has no value" }
        map[key] = args[i + 1]
        i += 2
    }
    return CliArgs(
        dataFile = Paths.get(map.getValue("--data")),
        opsFile = Paths.get(map.getValue("--ops")),
        scenarioName = map.getValue("--scenario"),
        clusterEndpoints = Paths.get(map.getValue("--cluster-endpoints")),
        outputDir = Paths.get(map.getValue("--output")),
    )
}
```

- [ ] **Step 2: Verify compile**

`cd gridgain-demo-data-generator && ./gradlew compileKotlin`

- [ ] **Step 3: Smoke test (without cluster)**

```bash
java -cp gridgain-demo-data-generator/build/libs/gridgain-demo-data-generator-0.0.1-SNAPSHOT.jar:<...full classpath...> \
  com.gridgain.demo.datagen.cli.Main \
  --data /tmp/data.yaml \
  --ops /tmp/ops.yaml \
  --scenario customer-load \
  --cluster-endpoints /tmp/client-endpoints.yaml \
  --output /tmp/output
```

Expect either a `MisconfigurationException` (file paths fake) or successful run. Either way, the JAR is structurally invocable — that's what we need.

- [ ] **Step 4: Republish**

`./gradlew publishToMavenLocal`

- [ ] **Step 5: Commit**

```bash
git add gridgain-demo-data-generator/src/main/kotlin/com/gridgain/demo/datagen/cli/Main.kt
git commit -m "feat(datagen): add CLI entry point for plugin invocation"
```

---

### Task 3: Plugin core action — `DataGenerateAction`

**Files:** Create `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/datagen/DataGenerateAction.kt`.

Build-tool-agnostic class. Constructor takes plugin context (config file paths, output dir, cluster name). `execute()` forks the data-generator JVM, captures stdout/stderr, parses `result.yaml`, returns a typed outcome.

- [ ] **Step 1: Implement DataGenerateAction**

```kotlin
package com.gridgain.demo.core.datagen

import com.gridgain.demo.core.exceptions.MisconfigurationException
import com.gridgain.demo.core.logging.DemoLogger
import java.io.File
import java.nio.file.Path

data class DataGenerateRequest(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpointsFile: Path,
    val outputDir: Path,
    val classpath: List<File>,
    val javaExecutable: File,
)

data class DataGenerateOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class DataGenerateAction(private val logger: DemoLogger) {

    fun execute(request: DataGenerateRequest): DataGenerateOutcome {
        require(request.dataFile.toFile().exists()) {
            "data.yaml not found at ${request.dataFile}"
        }
        require(request.opsFile.toFile().exists()) {
            "ops.yaml not found at ${request.opsFile}"
        }
        require(request.clusterEndpointsFile.toFile().exists()) {
            "client-endpoints.yaml not found at ${request.clusterEndpointsFile}"
        }

        val cpString = request.classpath.joinToString(File.pathSeparator) { it.absolutePath }
        val cmd = listOf(
            request.javaExecutable.absolutePath,
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "-cp", cpString,
            "com.gridgain.demo.datagen.cli.Main",
            "--data", request.dataFile.toAbsolutePath().toString(),
            "--ops", request.opsFile.toAbsolutePath().toString(),
            "--scenario", request.scenarioName,
            "--cluster-endpoints", request.clusterEndpointsFile.toAbsolutePath().toString(),
            "--output", request.outputDir.toAbsolutePath().toString(),
        )

        logger.lifecycle("forking data generator: ${cmd.joinToString(" ")}")

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            logger.warn("data generator exited with code $exitCode")
            logger.warn("stdout:\n$stdout")
            logger.warn("stderr:\n$stderr")
        } else {
            logger.lifecycle("data generator completed successfully")
            if (stdout.isNotBlank()) logger.info("stdout:\n$stdout")
        }

        return DataGenerateOutcome(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }
}
```

- [ ] **Step 2: Smoke build**

```bash
cd gridgain-demo-gradle-plugin
./gradlew compileKotlin
```

If `MisconfigurationException` and `DemoLogger` exist in the plugin's `core/`, this should compile.

- [ ] **Step 3: Commit (in plugin repo)**

```bash
cd gridgain-demo-gradle-plugin
git add src/main/kotlin/com/gridgain/demo/core/datagen/DataGenerateAction.kt
git commit -m "feat(plugin): add DataGenerateAction core class for forked data-generator runs"
```

---

### Task 4: Add data-generator dep to plugin

**Files:** `gridgain-demo-gradle-plugin/build.gradle.kts`.

Add `implementation("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")`. Use `compileOnly` if the plugin only needs the type for fork-classpath assembly and never instantiates data-generator types directly; for Plan 8 we use `implementation` so future plugin code can inspect the data-generator's typed configs (e.g., parse `ops.yaml` to enumerate scenario names for tab completion).

- [ ] **Step 1: Modify plugin build**

In `gridgain-demo-gradle-plugin/build.gradle.kts`, add to `dependencies { ... }`:
```kotlin
implementation("com.gridgain.demo:gridgain-demo-data-generator:0.0.1-SNAPSHOT")
```

- [ ] **Step 2: Verify resolution**

```bash
cd gridgain-demo-gradle-plugin
./gradlew --no-daemon build -x test
```

Expected: BUILD SUCCESSFUL. If the data-generator artifact is unresolved, run `cd ../gridgain-demo-data-generator && ./gradlew publishToMavenLocal && cd ../gridgain-demo-gradle-plugin` and retry.

- [ ] **Step 3: Commit**

```bash
cd gridgain-demo-gradle-plugin
git add build.gradle.kts
git commit -m "chore(plugin): add gridgain-demo-data-generator dep"
```

---

### Task 5: Plugin Gradle task — `DataGenerateTask`

**Files:** Create `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/plugin/tasks/DataGenerateTask.kt`.

Gradle task wrapper. Reads project properties for paths, builds a `DataGenerateRequest`, calls `DataGenerateAction.execute()`, fails the task if the fork's exit code is non-zero.

- [ ] **Step 1: Implement DataGenerateTask**

```kotlin
package com.gridgain.demo.plugin.tasks

import com.gridgain.demo.core.datagen.DataGenerateAction
import com.gridgain.demo.core.datagen.DataGenerateRequest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject

abstract class DataGenerateTask @Inject constructor() : DefaultTask() {

    @get:Input
    @get:Option(option = "scenario", description = "Name of the scenario to run")
    abstract var scenarioName: String

    @get:Input
    @get:Optional
    @get:Option(option = "data", description = "Path to data.yaml (defaults next to demoConfigFile)")
    var dataPath: String? = null

    @get:Input
    @get:Optional
    @get:Option(option = "ops", description = "Path to ops.yaml (defaults next to demoConfigFile)")
    var opsPath: String? = null

    @TaskAction
    fun run() {
        val demoConfig = (project.findProperty("demoConfigFile") as? String)
            ?: throw GradleException("demoConfigFile project property must be set")
        val demoOutput = (project.findProperty("demoOutputDirectory") as? String)
            ?: throw GradleException("demoOutputDirectory project property must be set")

        val configDir = Paths.get(demoConfig).parent
        val data = dataPath?.let { Paths.get(it) } ?: configDir.resolve("data.yaml")
        val ops = opsPath?.let { Paths.get(it) } ?: configDir.resolve("ops.yaml")
        val output = Paths.get(demoOutput)
        val endpoints = output.resolve("client").resolve("client-endpoints.yaml")

        // Build the fork classpath from the data-generator's runtime configuration.
        val cp = project.configurations.getByName("runtimeClasspath").files.toList()

        val javaHome = System.getProperty("java.home") ?: throw GradleException("java.home not set")
        val javaExe = Paths.get(javaHome).resolve("bin").resolve("java").toFile()
        if (!javaExe.exists()) throw GradleException("java executable not found at $javaExe")

        val logger = com.gridgain.demo.core.logging.Slf4jDemoLogger(org.slf4j.LoggerFactory.getLogger("DataGenerate"))
        val action = DataGenerateAction(logger)
        val outcome = action.execute(
            DataGenerateRequest(
                dataFile = data,
                opsFile = ops,
                scenarioName = scenarioName,
                clusterEndpointsFile = endpoints,
                outputDir = output,
                classpath = cp,
                javaExecutable = javaExe,
            )
        )
        if (outcome.exitCode != 0) {
            throw GradleException("data generator failed (exit ${outcome.exitCode}); see stderr above")
        }
    }
}
```

(Apologies — the `@get:Input` and `@get:Optional` imports need adding; see Step 2.)

- [ ] **Step 2: Imports and registration**

Add at the top of the file:
```kotlin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
```

In `GridGainDemoPlugin.kt`, register the task:
```kotlin
project.tasks.register("dataGenerate", DataGenerateTask::class.java)
```

- [ ] **Step 3: Verify compile**

`./gradlew compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/plugin/tasks/DataGenerateTask.kt src/main/kotlin/com/gridgain/demo/plugin/GridGainDemoPlugin.kt
git commit -m "feat(plugin): add dataGenerate gradle task"
```

---

### Task 6: End-to-end smoke

Run the full chain against the live `taxi-demo-gcp-8a` cluster.

- [ ] **Step 1: Refresh maven local**

```bash
cd gridgain-demo-data-generator && ./gradlew publishToMavenLocal && cd ..
cd gridgain-demo-gradle-plugin && ./gradlew publishToMavenLocal && cd ..
```

- [ ] **Step 2: Author a small ops.yaml + data.yaml under TaxiDemo**

`TaxiDemo/data.yaml`:
```yaml
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.10
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

`TaxiDemo/ops.yaml`:
```yaml
schema_version: 2
targets:
  - name: gg8-trip
    kind: gg8-kv
    cluster_name: taxi-demo-gcp-8a
scenarios:
  - name: customer-load
    target: gg8-trip
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 50 }
    duration: { kind: count, value: 200 }
    read_ratio: 0.10
```

(`transaction_scope` omitted — defaults to `none`.)

In `TaxiDemo/gradle.properties`:
```
demoConfigFile=...   # if not already set
demoOutputDirectory=build/gridgain/output
```

- [ ] **Step 3: Run the task**

```bash
cd TaxiDemo && ./gradlew dataGenerate --scenario customer-load --info
```

Expected: success. `TaxiDemo/build/gridgain/output/data-generator/runs/<run-id>/result.yaml` should contain:
```yaml
scenario_name: customer-load
achieved_rate: ~50
error_count: 0
success_count: 200
stop_reason: count reached
wall_time: PT~4S
```

The `data_gen_test` cache (or whatever the plugin's data lands in — by default the schema name `customer`) should now have ~180 entries (some keys reused via update_ratio).

- [ ] **Step 4: Failure modes to verify**

- Bad scenario name: `-Pscenario=missing` → task fails with "Scenario 'missing' not declared".
- Cluster offline: task surfaces fork's exit-1, the connect error visible in stderr.
- Missing data.yaml: task fails before the fork with a clear message.

---

### Task 7: Document the workflow

**Files:** Update `gridgain-demo-data-generator/CLAUDE.md` §10 (Plugin & UI Integration) with a concrete invocation example, and update `gridgain-demo-data-generator/docs/superpowers/ROADMAP.md` Operating Modes section to mark the plugin-driven path as live.

- [ ] **Step 1: Update CLAUDE.md §10**

Add a subsection after the existing "Plugin invocation" paragraph:

```markdown
### Concrete invocation
After running `cd gridgain-demo-data-generator && ./gradlew publishToMavenLocal`
once (re-run after every data-generator code change), the generator is invokable
from the plugin via:

    cd TaxiDemo
    ./gradlew dataGenerate --scenario <name>

(There is no top-level multi-project gradle build at the workspace root; each
sibling — TaxiDemo, the plugin, the data-generator — is its own project. TaxiDemo
`includeBuild`s the plugin so plugin code changes are picked up without
publishing.)

The plugin task forks a JVM with the data-generator's classpath, passes the
plugin's existing demoConfigFile / demoOutputDirectory paths through, and writes
the result to `<demoOutputDirectory>/data-generator/runs/<run-id>/result.yaml`.
```

- [ ] **Step 2: Update ROADMAP.md**

Mark Plan 8 as completed under the "Remaining Plans" section, move it to a new
"Completed" section near the top.

- [ ] **Step 3: Commit**

```bash
cd gridgain-demo-data-generator
git add CLAUDE.md docs/superpowers/ROADMAP.md
git commit -m "docs(datagen): document plugin invocation workflow"
```

---

## Verification

After Plan 8 lands:
- `cd TaxiDemo && ./gradlew dataGenerate --scenario customer-load` runs end-to-end and
  produces a result.yaml.
- Maven-local publish workflow documented and routine.
- Forked-JVM classpath strategy documented, sidestepping the GG8/GG9 ignite-core
  conflict.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §10 Plugin → generator dependency only | Tasks 1, 4 |
| §10 Plugin invocation surfaces results into plugin run log | Tasks 3, 5 |
| §10 Plugin task constructs paths from plugin context | Task 5 |
| §10 Forking model isolates GG8 vs GG9 ignite-core | Task 3 (process fork) |

**Out of scope of this plan (deferred):**
- GG9 KV target — Plan 7.
- Provisioning emit/apply — Plan 9.
- State persistence (KeyRegistry across runs) — Plan 10.
- OTel — Plan 11.
- UI integration — out of scope per spec §10 ("UI integration is loose").

---

## Known caveats

1. **Cache mode**: with `transaction_scope: business_event`, the plugin's existing
   cache provisioning needs to create caches in `TRANSACTIONAL` mode. Today the
   data-generator silently uses ATOMIC defaults; Plan 9 handles this. For Plan 8,
   keep all smoke scenarios at `transaction_scope: none` (the default).
2. **Forking overhead**: each `dataGenerate` invocation spawns a JVM,
   parses configs, opens a thin client. Acceptable for demo-sized work (single
   scenario per invocation). If we ever want to batch many scenarios, revisit.
3. **`@get:Input` on a `var String` property**: Gradle's task properties API
   prefers `Property<String>` for laziness. The task code above uses plain
   `var` for simplicity. If incremental-build correctness becomes an issue,
   migrate to `abstract val scenarioName: Property<String>` later.
