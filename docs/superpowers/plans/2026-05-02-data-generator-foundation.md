# Data Generator — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lay down a build-tool-agnostic Kotlin foundation for `gridgain-demo-data-generator` that parses, migrates, and validates two yaml config files (`data.yaml`, `ops.yaml`) and produces typed config objects, plus shared infrastructure (errors, logger, run-id, output layout) every later plan depends on. **No data generation, no cluster I/O, no DataFaker integration in this plan.**

**Architecture:** A single Kotlin/JVM module structured by responsibility (`config/`, `errors/`, `logging/`, `output/`, `runtime/`). Configuration parsing follows a five-stage pipeline — read → migrate → JSONSchema validate → cross-element validate → deserialize — with each stage isolated in its own class. Migration support is generic over a list of `ConfigMigration` steps and reused by both `data.yaml` and `ops.yaml`. The v1 typed model is intentionally minimal and permissive: it parses a top-level envelope (`schema_version` required, body permissive) so later plans can bump versions and add fields without rewriting the foundation.

**Tech Stack:**
- Kotlin 2.2.20 / JVM 17 (already configured)
- Jackson 2.17.2 + `jackson-dataformat-yaml` + `jackson-module-kotlin` (mirroring the plugin)
- `com.networknt:json-schema-validator:1.5.9` (mirroring the plugin)
- SnakeYAML 1.33 forced (project-wide rule)
- SLF4J API 2.0.x for logging
- JUnit 5 + AssertJ + kotlin-test for tests

---

## File Structure

```
gridgain-demo-data-generator/
├── build.gradle.kts                                                       (modify)
├── settings.gradle.kts                                                    (existing — leave)
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   ├── ConfiguredVersions.kt        # version constants
│   │   ├── ConfigMigration.kt           # interface + runner
│   │   ├── DataConfig.kt                # v1 typed envelope
│   │   ├── OpsConfig.kt                 # v1 typed envelope
│   │   ├── JsonSchemaValidator.kt       # wraps networknt
│   │   ├── CrossElementValidator.kt     # interface + v1 no-op impl
│   │   └── ConfigurationParser.kt       # pipeline orchestrator
│   ├── errors/
│   │   └── DomainExceptions.kt          # DomainException + Misconfiguration + CorruptedState
│   ├── logging/
│   │   └── DataGenLogger.kt             # interface + Slf4j impl
│   ├── output/
│   │   └── OutputLayout.kt              # derives provisioning/, runs/<run-id>/, state/
│   └── runtime/
│       └── RunId.kt                     # generates lexicographic run identifiers
├── src/main/resources/schema/
│   ├── data/v1.schema.json              # permissive envelope: schema_version=1
│   └── ops/v1.schema.json               # permissive envelope: schema_version=1
└── src/test/
    ├── kotlin/com/gridgain/demo/datagen/
    │   ├── config/
    │   │   ├── ConfigMigrationTest.kt
    │   │   ├── JsonSchemaValidatorTest.kt
    │   │   ├── CrossElementValidatorTest.kt
    │   │   └── ConfigurationParserTest.kt
    │   ├── errors/DomainExceptionsTest.kt
    │   ├── logging/Slf4jDataGenLoggerTest.kt
    │   ├── output/OutputLayoutTest.kt
    │   └── runtime/RunIdTest.kt
    └── resources/
        ├── data-v1-minimal.yaml
        └── ops-v1-minimal.yaml
```

---

### Task 1: Wire build dependencies

**Files:**
- Modify: `build.gradle.kts` (full file rewrite — current file is a 22-line stub)

- [ ] **Step 1: Replace `build.gradle.kts` with the full dependency set**

```kotlin
plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.gridgain.demo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // DataFaker is wired now so later plans don't need to revisit build config.
    implementation("net.datafaker:datafaker:2.5.4")

    // Jackson stack for YAML parsing — mirrors the plugin's choice.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")

    // JSONSchema validation — mirrors the plugin's choice.
    implementation("com.networknt:json-schema-validator:1.5.9")

    // SLF4J API for logging. No binding in main; tests use slf4j-simple.
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

// Project-wide rule: SnakeYAML forced to 1.33 to prevent Android variant conflicts.
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

- [ ] **Step 2: Verify the build resolves**

Run: `./gradlew --no-daemon build -x test`
Expected: `BUILD SUCCESSFUL`. (No source files yet, but dependency resolution and Kotlin compilation should succeed.)

- [ ] **Step 3: Commit**

```bash
cd gridgain-demo-data-generator
git add build.gradle.kts
git commit -m "chore(datagen): wire jackson, json-schema-validator, slf4j, and test deps"
```

---

### Task 2: Domain exceptions

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/errors/DomainExceptions.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/errors/DomainExceptionsTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/errors/DomainExceptionsTest.kt`:

```kotlin
package com.gridgain.demo.datagen.errors

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class DomainExceptionsTest {

    @Test
    fun `MisconfigurationException carries message and cause`() {
        val cause = IllegalStateException("root")
        val ex = MisconfigurationException("bad config", cause)
        assertThat(ex).isInstanceOf(DomainException::class.java)
        assertThat(ex.message).isEqualTo("bad config")
        assertThat(ex.cause).isSameAs(cause)
    }

    @Test
    fun `CorruptedStateException carries message and cause`() {
        val ex = CorruptedStateException("state.yaml is corrupt")
        assertThat(ex).isInstanceOf(DomainException::class.java)
        assertThat(ex.message).isEqualTo("state.yaml is corrupt")
        assertThat(ex.cause).isNull()
    }

    @Test
    fun `DomainException is a RuntimeException`() {
        val ex = DomainException("anything")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.errors.DomainExceptionsTest'`
Expected: FAIL — `Unresolved reference: DomainException` (or similar; classes don't exist yet).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/errors/DomainExceptions.kt`:

```kotlin
package com.gridgain.demo.datagen.errors

open class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MisconfigurationException(
    message: String, cause: Throwable? = null
) : DomainException(message, cause)

class CorruptedStateException(
    message: String, cause: Throwable? = null
) : DomainException(message, cause)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.errors.DomainExceptionsTest'`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/errors src/test/kotlin/com/gridgain/demo/datagen/errors
git commit -m "feat(datagen): add DomainException hierarchy with Misconfiguration and CorruptedState"
```

---

### Task 3: DataGenLogger interface and SLF4J binding

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/logging/DataGenLogger.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/logging/Slf4jDataGenLoggerTest.kt`

The data generator must NOT depend on the plugin, so we introduce a small mirror of the plugin's `DemoLogger` shape. The interface is intentionally narrower than the plugin's: only the calls Plan 1 actually uses (`lifecycle`, `info`, `warn`, `error`, `debug`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/logging/Slf4jDataGenLoggerTest.kt`:

```kotlin
package com.gridgain.demo.datagen.logging

import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import kotlin.test.Test

class Slf4jDataGenLoggerTest {

    @Test
    fun `lifecycle info warn debug do not throw`() {
        val log = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        log.lifecycle("alpha")
        log.info("beta")
        log.warn("gamma")
        log.debug("delta")
        // No assertion needed: success is "did not throw".
    }

    @Test
    fun `error accepts an optional throwable without throwing`() {
        val log = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        log.error("boom", IllegalStateException("cause"))
        log.error("boom-no-cause")
    }

    @Test
    fun `DataGenLogger is the public interface`() {
        val log: DataGenLogger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        assertThat(log).isInstanceOf(DataGenLogger::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.logging.Slf4jDataGenLoggerTest'`
Expected: FAIL — `Unresolved reference: Slf4jDataGenLogger`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/logging/DataGenLogger.kt`:

```kotlin
package com.gridgain.demo.datagen.logging

import org.slf4j.Logger

interface DataGenLogger {
    fun lifecycle(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
    fun debug(message: String)
}

class Slf4jDataGenLogger(private val log: Logger) : DataGenLogger {
    override fun lifecycle(message: String) = log.info(message)
    override fun info(message: String) = log.info(message)
    override fun warn(message: String) = log.warn(message)
    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) log.error(message, throwable) else log.error(message)
    }
    override fun debug(message: String) = log.debug(message)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.logging.Slf4jDataGenLoggerTest'`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/logging src/test/kotlin/com/gridgain/demo/datagen/logging
git commit -m "feat(datagen): add DataGenLogger interface and Slf4j binding"
```

---

### Task 4: Run identifiers

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/runtime/RunId.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/runtime/RunIdTest.kt`

A run id identifies one generator invocation, used to name `runs/<run-id>/` directories per the spec.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/runtime/RunIdTest.kt`:

```kotlin
package com.gridgain.demo.datagen.runtime

import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class RunIdTest {

    @Test
    fun `run id starts with utc date in YYYYMMDD-HHMMSS form`() {
        val fixed = Clock.fixed(Instant.parse("2026-05-02T13:14:15Z"), ZoneOffset.UTC)
        val id = RunId.generate(fixed)
        assertThat(id).startsWith("20260502-131415-")
    }

    @Test
    fun `run id has a random suffix and is filesystem-safe`() {
        val a = RunId.generate()
        val b = RunId.generate()
        assertThat(a).isNotEqualTo(b)
        assertThat(a).matches("^[0-9]{8}-[0-9]{6}-[a-z0-9]{6}$")
    }

    @Test
    fun `run ids generated in time order sort lexicographically`() {
        val earlier = Clock.fixed(Instant.parse("2026-05-02T13:14:15Z"), ZoneOffset.UTC)
        val later = Clock.fixed(Instant.parse("2026-05-02T13:14:16Z"), ZoneOffset.UTC)
        val a = RunId.generate(earlier)
        val b = RunId.generate(later)
        assertThat(a < b).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.runtime.RunIdTest'`
Expected: FAIL — `Unresolved reference: RunId`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/runtime/RunId.kt`:

```kotlin
package com.gridgain.demo.datagen.runtime

import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RunId {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val RNG = SecureRandom()

    fun generate(clock: Clock = Clock.systemUTC()): String {
        val timestamp = FORMAT.format(clock.instant())
        val suffix = (1..6).map { ALPHABET[RNG.nextInt(ALPHABET.length)] }.joinToString("")
        return "$timestamp-$suffix"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.runtime.RunIdTest'`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/runtime src/test/kotlin/com/gridgain/demo/datagen/runtime
git commit -m "feat(datagen): add RunId generator with UTC timestamp and random suffix"
```

---

### Task 5: Output directory layout

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/output/OutputLayout.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/output/OutputLayoutTest.kt`

Per spec §6: all file output lives under `<demoOutputDirectory>/data-generator/` with subdirectories `provisioning/`, `runs/<run-id>/`, and `state/`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/output/OutputLayoutTest.kt`:

```kotlin
package com.gridgain.demo.datagen.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class OutputLayoutTest {

    @Test
    fun `derives a data-generator subdirectory under the demo output root`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        assertThat(layout.generatorRoot).isEqualTo(root.resolve("data-generator"))
    }

    @Test
    fun `derives provisioning and state directories`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        assertThat(layout.provisioning).isEqualTo(root.resolve("data-generator/provisioning"))
        assertThat(layout.state).isEqualTo(root.resolve("data-generator/state"))
        assertThat(layout.stateFile).isEqualTo(root.resolve("data-generator/state/state.yaml"))
    }

    @Test
    fun `derives a per-run directory from a run id`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        val runDir = layout.runDir("20260502-131415-abc123")
        assertThat(runDir).isEqualTo(root.resolve("data-generator/runs/20260502-131415-abc123"))
        assertThat(layout.resultFile("20260502-131415-abc123"))
            .isEqualTo(runDir.resolve("result.yaml"))
        assertThat(layout.runLogFile("20260502-131415-abc123"))
            .isEqualTo(runDir.resolve("run.log.yaml"))
    }

    @Test
    fun `ensure creates all required directories`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        layout.ensureBaseDirectories()
        assertThat(layout.generatorRoot.toFile()).exists().isDirectory()
        assertThat(layout.provisioning.toFile()).exists().isDirectory()
        assertThat(layout.state.toFile()).exists().isDirectory()
        // runs/<run-id>/ is created on demand, not by ensureBaseDirectories.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.output.OutputLayoutTest'`
Expected: FAIL — `Unresolved reference: OutputLayout`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/output/OutputLayout.kt`:

```kotlin
package com.gridgain.demo.datagen.output

import java.nio.file.Files
import java.nio.file.Path

class OutputLayout(demoOutputDirectory: Path) {
    val generatorRoot: Path = demoOutputDirectory.resolve("data-generator")
    val provisioning: Path = generatorRoot.resolve("provisioning")
    val state: Path = generatorRoot.resolve("state")
    val stateFile: Path = state.resolve("state.yaml")
    private val runs: Path = generatorRoot.resolve("runs")

    fun runDir(runId: String): Path = runs.resolve(runId)
    fun resultFile(runId: String): Path = runDir(runId).resolve("result.yaml")
    fun runLogFile(runId: String): Path = runDir(runId).resolve("run.log.yaml")

    fun ensureBaseDirectories() {
        Files.createDirectories(generatorRoot)
        Files.createDirectories(provisioning)
        Files.createDirectories(state)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.output.OutputLayoutTest'`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/output src/test/kotlin/com/gridgain/demo/datagen/output
git commit -m "feat(datagen): add OutputLayout for provisioning, runs, and state directories"
```

---

### Task 6: Schema version constants

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`

This file is tiny but must exist before the parser references the constants.

- [ ] **Step 1: Write the file directly (no test — these are constants used by tested code below)**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt`:

```kotlin
package com.gridgain.demo.datagen.config

const val CURRENT_DATA_SCHEMA_VERSION: Int = 1
const val CURRENT_OPS_SCHEMA_VERSION: Int = 1
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ConfiguredVersions.kt
git commit -m "feat(datagen): add CURRENT_DATA_SCHEMA_VERSION and CURRENT_OPS_SCHEMA_VERSION"
```

---

### Task 7: ConfigMigration interface and runner

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigMigration.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/ConfigMigrationTest.kt`

This mirrors the plugin's `ConfigMigration`/`ConfigMigrationRunner` API, but the runner takes its migration list as a constructor argument so the same class serves both `data.yaml` and `ops.yaml`. The runner reads, optionally rewrites, the file and returns the up-to-date YAML text for downstream parsing.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/config/ConfigMigrationTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class ConfigMigrationTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    private fun writeYaml(@TempDir dir: Path, name: String, body: String): Path {
        val file = dir.resolve(name)
        file.writeText(body)
        return file
    }

    @Test
    fun `config at current version passes through unchanged`(@TempDir dir: Path) {
        val original = "schema_version: 1\nschemas: []\n"
        val file = writeYaml(dir, "data.yaml", original)
        val runner = ConfigMigrationRunner(migrations = emptyList())
        val text = runner.ensureCurrentVersion(file.toFile(), targetVersion = 1, logger = logger)
        assertThat(text.trim()).isEqualTo(original.trim())
        assertThat(file.toFile().readText()).isEqualTo(original)
    }

    @Test
    fun `config above current version is rejected with remediation`(@TempDir dir: Path) {
        val file = writeYaml(dir, "data.yaml", "schema_version: 99\nschemas: []\n")
        val runner = ConfigMigrationRunner(migrations = emptyList())
        assertThatThrownBy {
            runner.ensureCurrentVersion(file.toFile(), targetVersion = 1, logger = logger)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("schema_version 99")
            .hasMessageContaining("only supports up to schema_version 1")
            .hasMessageContaining("Please upgrade")
    }

    @Test
    fun `missing schema_version is rejected explicitly`(@TempDir dir: Path) {
        val file = writeYaml(dir, "data.yaml", "schemas: []\n")
        val runner = ConfigMigrationRunner(migrations = emptyList())
        assertThatThrownBy {
            runner.ensureCurrentVersion(file.toFile(), targetVersion = 1, logger = logger)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("schema_version")
            .hasMessageContaining("required")
    }

    @Test
    fun `runner walks the migration chain in order and writes the file back`(@TempDir dir: Path) {
        val original = "schema_version: 1\nfoo: alpha\n"
        val file = writeYaml(dir, "data.yaml", original)

        val v1to2 = object : ConfigMigration {
            override val fromVersion = 1
            override val toVersion = 2
            override val description = "rename foo to bar"
            override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
                val value = yaml.remove("foo") ?: error("foo expected")
                yaml["bar"] = value
                return yaml
            }
        }
        val v2to3 = object : ConfigMigration {
            override val fromVersion = 2
            override val toVersion = 3
            override val description = "uppercase bar"
            override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
                yaml["bar"] = (yaml["bar"] as String).uppercase()
                return yaml
            }
        }

        val runner = ConfigMigrationRunner(migrations = listOf(v1to2, v2to3))
        val text = runner.ensureCurrentVersion(file.toFile(), targetVersion = 3, logger = logger)

        @Suppress("UNCHECKED_CAST")
        val parsed = Yaml().load<Map<String, Any>>(text)
        assertThat(parsed["schema_version"]).isEqualTo(3)
        assertThat(parsed["bar"]).isEqualTo("ALPHA")
        assertThat(parsed).doesNotContainKey("foo")

        @Suppress("UNCHECKED_CAST")
        val onDisk = Yaml().load<Map<String, Any>>(file.toFile().readText())
        assertThat(onDisk["schema_version"]).isEqualTo(3)
        assertThat(onDisk["bar"]).isEqualTo("ALPHA")
    }

    @Test
    fun `missing migration step is rejected with remediation`(@TempDir dir: Path) {
        val file = writeYaml(dir, "data.yaml", "schema_version: 1\nfoo: alpha\n")
        val runner = ConfigMigrationRunner(migrations = emptyList()) // no v1->v2 step

        assertThatThrownBy {
            runner.ensureCurrentVersion(file.toFile(), targetVersion = 2, logger = logger)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("No migration path from schema_version 1 to 2")
    }

    @Test
    fun `empty file is rejected with remediation`(@TempDir dir: Path) {
        val file = writeYaml(dir, "data.yaml", "")
        val runner = ConfigMigrationRunner(migrations = emptyList())
        assertThatThrownBy {
            runner.ensureCurrentVersion(file.toFile(), targetVersion = 1, logger = logger)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("empty or not a valid YAML mapping")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.ConfigMigrationTest'`
Expected: FAIL — `Unresolved reference: ConfigMigration`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigMigration.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.DataGenLogger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

interface ConfigMigration {
    val fromVersion: Int
    val toVersion: Int
    val description: String
    fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any>
}

class ConfigMigrationRunner(private val migrations: List<ConfigMigration>) {

    fun ensureCurrentVersion(configFile: File, targetVersion: Int, logger: DataGenLogger): String {
        val yamlText = configFile.readText()
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val rawMap = yaml.load<Any>(yamlText) as? MutableMap<String, Any>
            ?: throw MisconfigurationException(
                "Configuration file '${configFile.name}' is empty or not a valid YAML mapping. " +
                "Provide a yaml document with a top-level 'schema_version' field."
            )

        val fileVersion = (rawMap["schema_version"] as? Number)?.toInt()
            ?: throw MisconfigurationException(
                "Configuration file '${configFile.name}' is missing the required 'schema_version' field. " +
                "Add 'schema_version: $targetVersion' as the first line."
            )

        if (fileVersion == targetVersion) return yamlText

        if (fileVersion > targetVersion) {
            throw MisconfigurationException(
                "Configuration file '${configFile.name}' uses schema_version $fileVersion, " +
                "but this version of the data generator only supports up to schema_version $targetVersion. " +
                "Please upgrade the data generator to a version that supports this config file."
            )
        }

        var current = fileVersion
        var map = rawMap
        while (current < targetVersion) {
            val migration = migrations.find { it.fromVersion == current }
                ?: throw MisconfigurationException(
                    "No migration path from schema_version $current to $targetVersion. " +
                    "Cannot auto-upgrade configuration file '${configFile.name}'. " +
                    "Update the file by hand or downgrade the data generator."
                )
            logger.lifecycle(
                "Migrating ${configFile.name}: schema_version $current -> ${migration.toVersion} (${migration.description})"
            )
            map = migration.migrate(map)
            map["schema_version"] = migration.toVersion
            current = migration.toVersion
        }

        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indicatorIndent = 0
            indent = 2
        }
        val updatedText = Yaml(dumperOptions).dump(map)
        configFile.writeText(updatedText)
        logger.lifecycle("Updated '${configFile.name}' to schema_version $targetVersion.")

        return updatedText
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.ConfigMigrationTest'`
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ConfigMigration.kt src/test/kotlin/com/gridgain/demo/datagen/config/ConfigMigrationTest.kt
git commit -m "feat(datagen): add ConfigMigration interface and ConfigMigrationRunner"
```

---

### Task 8: v1 typed envelopes for `data.yaml` and `ops.yaml`

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`

The v1 typed model is intentionally minimal: just the `schema_version` field and a permissive body (raw map) so this plan does not pre-empt design decisions that belong in Plans 2–4. Later plans tighten the model and (when they make breaking changes) bump versions and add migrations.

- [ ] **Step 1: Write the failing tests**

(The data classes are exercised by `ConfigurationParserTest` in Task 12; this task adds them as inputs for that test. Keep the data classes minimal here and proceed.)

- [ ] **Step 2: Write the minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * v1 envelope for data.yaml. The body is intentionally permissive — Plans 2 and beyond
 * tighten the schema and add typed fields. The body map preserves any extra keys the user
 * supplies, so this v1 envelope can read forward-versioned files for inspection (validation
 * is enforced separately by JsonSchemaValidator).
 */
data class DataConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    @get:JsonAnyGetter val body: MutableMap<String, Any?> = mutableMapOf()
) {
    @JsonAnySetter
    @JsonIgnore
    fun put(key: String, value: Any?) { body[key] = value }
}
```

Create `src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * v1 envelope for ops.yaml. Same permissive design as DataConfig.
 */
data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    @get:JsonAnyGetter val body: MutableMap<String, Any?> = mutableMapOf()
) {
    @JsonAnySetter
    @JsonIgnore
    fun put(key: String, value: Any?) { body[key] = value }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt
git commit -m "feat(datagen): add v1 DataConfig and OpsConfig envelopes"
```

---

### Task 9: JSONSchema files for v1

**Files:**
- Create: `src/main/resources/schema/data/v1.schema.json`
- Create: `src/main/resources/schema/ops/v1.schema.json`

v1 schemas validate only the envelope. They require `schema_version` to equal `1` and forbid unrelated top-level types. They permit additional properties so later plans can add typed fields and bump versions deliberately.

- [ ] **Step 1: Write the data v1 schema**

Create `src/main/resources/schema/data/v1.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://gridgain.com/datagen/data-v1.schema.json",
  "title": "Data Generator data.yaml v1",
  "type": "object",
  "required": ["schema_version"],
  "properties": {
    "schema_version": { "const": 1 }
  },
  "additionalProperties": true
}
```

- [ ] **Step 2: Write the ops v1 schema**

Create `src/main/resources/schema/ops/v1.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://gridgain.com/datagen/ops-v1.schema.json",
  "title": "Data Generator ops.yaml v1",
  "type": "object",
  "required": ["schema_version"],
  "properties": {
    "schema_version": { "const": 1 }
  },
  "additionalProperties": true
}
```

- [ ] **Step 3: Verify resources package up correctly**

Run: `./gradlew processResources`
Expected: `BUILD SUCCESSFUL`. Confirm files appear under `build/resources/main/schema/`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/schema/
git commit -m "feat(datagen): add v1 JSONSchema envelopes for data.yaml and ops.yaml"
```

---

### Task 10: JsonSchemaValidator wrapper

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidatorTest.kt`

Wraps `com.networknt:json-schema-validator` and turns its messages into `MisconfigurationException` with remediation guidance.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class JsonSchemaValidatorTest {

    @Test
    fun `valid v1 data yaml passes`() {
        val yaml = "schema_version: 1\n"
        assertThatCode { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `valid v1 ops yaml passes`() {
        val yaml = "schema_version: 1\n"
        assertThatCode { JsonSchemaValidator.validateOps(yaml, fileName = "ops.yaml") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `wrong schema_version produces a remediation message naming the file`() {
        val yaml = "schema_version: 99\n"
        assertThatThrownBy { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml") }
            .isInstanceOf(MisconfigurationException::class.java)
            .satisfies({ ex ->
                assertThat(ex.message).contains("data.yaml")
                assertThat(ex.message).contains("schema_version")
            })
    }

    @Test
    fun `non-object root is rejected`() {
        val yaml = "- a\n- b\n"
        assertThatThrownBy { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml") }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("data.yaml")
    }

    @Test
    fun `unknown schema version requested throws on lookup`() {
        assertThatThrownBy { JsonSchemaValidator.validateData("schema_version: 1\n", fileName = "data.yaml", version = 999) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("Unknown data schema version 999")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.JsonSchemaValidatorTest'`
Expected: FAIL — `Unresolved reference: JsonSchemaValidator`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidator.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

object JsonSchemaValidator {

    private val yamlMapper: YAMLMapper = YAMLMapper()
    private val factory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    fun validateData(yamlText: String, fileName: String, version: Int = CURRENT_DATA_SCHEMA_VERSION) {
        validate(
            yamlText = yamlText,
            fileName = fileName,
            schemaResource = "/schema/data/v$version.schema.json",
            kind = "data",
            version = version,
        )
    }

    fun validateOps(yamlText: String, fileName: String, version: Int = CURRENT_OPS_SCHEMA_VERSION) {
        validate(
            yamlText = yamlText,
            fileName = fileName,
            schemaResource = "/schema/ops/v$version.schema.json",
            kind = "ops",
            version = version,
        )
    }

    private fun validate(yamlText: String, fileName: String, schemaResource: String, kind: String, version: Int) {
        val node: JsonNode = try {
            yamlMapper.readTree(yamlText)
        } catch (e: Exception) {
            throw MisconfigurationException(
                "Configuration file '$fileName' is not valid YAML: ${e.message}. " +
                "Verify the file is a UTF-8 yaml document.",
                cause = e
            )
        }

        if (!node.isObject) {
            throw MisconfigurationException(
                "Configuration file '$fileName' must be a yaml mapping (object) at the top level. " +
                "Found ${node.nodeType.name.lowercase()} instead."
            )
        }

        val schema: JsonSchema = loadSchema(schemaResource, kind, version)

        val errors = schema.validate(node)
        if (errors.isNotEmpty()) {
            val details = errors.joinToString(separator = "\n  - ", prefix = "  - ") { it.message }
            throw MisconfigurationException(
                "Configuration file '$fileName' failed JSONSchema validation against $kind v$version:\n$details\n" +
                "Fix each item above and re-run."
            )
        }
    }

    private fun loadSchema(schemaResource: String, kind: String, version: Int): JsonSchema {
        val stream = JsonSchemaValidator::class.java.getResourceAsStream(schemaResource)
            ?: throw MisconfigurationException(
                "Unknown $kind schema version $version. " +
                "This is a data-generator bug — the file '$schemaResource' is missing from the jar."
            )
        return stream.use { factory.getSchema(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.JsonSchemaValidatorTest'`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidator.kt src/test/kotlin/com/gridgain/demo/datagen/config/JsonSchemaValidatorTest.kt
git commit -m "feat(datagen): add JsonSchemaValidator with remediation-rich error messages"
```

---

### Task 11: CrossElementValidator skeleton

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/CrossElementValidatorTest.kt`

Per spec §6, cross-element validation enforces relation referential integrity, scenario-target capability compatibility, `null_rate` not on relation columns, and the affinity-vs-transaction warning. **Each of those rules concerns fields introduced in Plans 2–4 — none exist at v1.** This task installs the contract and a default no-op implementation; later plans will extend it.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/gridgain/demo/datagen/config/CrossElementValidatorTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import kotlin.test.Test

class CrossElementValidatorTest {

    @Test
    fun `default validator accepts a v1 envelope`() {
        val data = DataConfig(schemaVersion = 1)
        val ops = OpsConfig(schemaVersion = 1)
        val result = DefaultCrossElementValidator().validate(data, ops)
        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `validator returns a CrossElementValidationResult`() {
        val data = DataConfig(schemaVersion = 1)
        val ops = OpsConfig(schemaVersion = 1)
        assertThatCode { DefaultCrossElementValidator().validate(data, ops) }
            .doesNotThrowAnyException()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.CrossElementValidatorTest'`
Expected: FAIL — `Unresolved reference: CrossElementValidator` / `DefaultCrossElementValidator`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt`:

```kotlin
package com.gridgain.demo.datagen.config

data class CrossElementValidationResult(
    val errors: List<String>,
    val warnings: List<String>,
)

interface CrossElementValidator {
    fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult
}

/**
 * v1 default validator. v1 has no fields whose interaction can be checked yet — relations,
 * scenarios, targets, and null_rate are all introduced in Plans 2–4. Adding rules is a
 * matter of writing additional CrossElementValidator implementations and composing them
 * (see CompositeCrossElementValidator).
 */
class DefaultCrossElementValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult =
        CrossElementValidationResult(errors = emptyList(), warnings = emptyList())
}

class CompositeCrossElementValidator(
    private val validators: List<CrossElementValidator>
) : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (v in validators) {
            val r = v.validate(data, ops)
            errors += r.errors
            warnings += r.warnings
        }
        return CrossElementValidationResult(errors, warnings)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.CrossElementValidatorTest'`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/CrossElementValidator.kt src/test/kotlin/com/gridgain/demo/datagen/config/CrossElementValidatorTest.kt
git commit -m "feat(datagen): add CrossElementValidator interface with default and composite impls"
```

---

### Task 12: ConfigurationParser orchestration (happy path)

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt`
- Test:   `src/test/resources/data-v1-minimal.yaml`
- Test:   `src/test/resources/ops-v1-minimal.yaml`

Drives the full pipeline: read → migrate → JSONSchema validate → cross-element validate → deserialize. Returns a `ParsedConfiguration(data, ops)` tuple.

- [ ] **Step 1: Add minimal test fixtures**

Create `src/test/resources/data-v1-minimal.yaml`:

```yaml
schema_version: 1
```

Create `src/test/resources/ops-v1-minimal.yaml`:

```yaml
schema_version: 1
```

- [ ] **Step 2: Write the failing happy-path test**

Create `src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class ConfigurationParserTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    private fun copyResource(@TempDir dir: Path, resource: String, name: String): Path {
        val target = dir.resolve(name)
        ConfigurationParserTest::class.java.classLoader.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "missing resource $resource" }
            Files.copy(input, target)
        }
        return target
    }

    @Test
    fun `parses minimal v1 data and ops yaml end-to-end`(@TempDir dir: Path) {
        val data = copyResource(dir, "data-v1-minimal.yaml", "data.yaml")
        val ops = copyResource(dir, "ops-v1-minimal.yaml", "ops.yaml")
        val parser = ConfigurationParser(logger = logger)
        val parsed = parser.parse(dataFile = data.toFile(), opsFile = ops.toFile())
        assertThat(parsed.data.schemaVersion).isEqualTo(1)
        assertThat(parsed.ops.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `failure in JSONSchema stage surfaces as MisconfigurationException naming the file`(@TempDir dir: Path) {
        val data = dir.resolve("data.yaml").also { it.writeText("schema_version: 1\n") }
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 99\n") }
        val parser = ConfigurationParser(logger = logger)
        assertThatThrownBy { parser.parse(dataFile = data.toFile(), opsFile = ops.toFile()) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("ops.yaml")
    }

    @Test
    fun `cross-element validator errors are aggregated and surfaced`(@TempDir dir: Path) {
        val data = dir.resolve("data.yaml").also { it.writeText("schema_version: 1\n") }
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val rejecting = object : CrossElementValidator {
            override fun validate(d: DataConfig, o: OpsConfig) =
                CrossElementValidationResult(errors = listOf("nope"), warnings = emptyList())
        }
        val parser = ConfigurationParser(logger = logger, crossElementValidator = rejecting)
        assertThatThrownBy { parser.parse(dataFile = data.toFile(), opsFile = ops.toFile()) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("nope")
    }

    @Test
    fun `cross-element warnings are logged and do not throw`(@TempDir dir: Path) {
        val data = dir.resolve("data.yaml").also { it.writeText("schema_version: 1\n") }
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val warner = object : CrossElementValidator {
            override fun validate(d: DataConfig, o: OpsConfig) =
                CrossElementValidationResult(errors = emptyList(), warnings = listOf("careful"))
        }
        val parser = ConfigurationParser(logger = logger, crossElementValidator = warner)
        val parsed = parser.parse(dataFile = data.toFile(), opsFile = ops.toFile())
        assertThat(parsed.data.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `missing data file is rejected with remediation`(@TempDir dir: Path) {
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val parser = ConfigurationParser(logger = logger)
        assertThatThrownBy { parser.parse(dataFile = dir.resolve("missing.yaml").toFile(), opsFile = ops.toFile()) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("missing.yaml")
            .hasMessageContaining("does not exist")
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.ConfigurationParserTest'`
Expected: FAIL — `Unresolved reference: ConfigurationParser`.

- [ ] **Step 4: Write minimal implementation**

Create `src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.DataGenLogger
import java.io.File

data class ParsedConfiguration(val data: DataConfig, val ops: OpsConfig)

class ConfigurationParser(
    private val logger: DataGenLogger,
    private val dataMigrationRunner: ConfigMigrationRunner = ConfigMigrationRunner(emptyList()),
    private val opsMigrationRunner: ConfigMigrationRunner = ConfigMigrationRunner(emptyList()),
    private val crossElementValidator: CrossElementValidator = DefaultCrossElementValidator(),
) {

    private val yamlMapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    fun parse(dataFile: File, opsFile: File): ParsedConfiguration {
        requireExists(dataFile)
        requireExists(opsFile)

        val dataYaml = dataMigrationRunner.ensureCurrentVersion(dataFile, CURRENT_DATA_SCHEMA_VERSION, logger)
        val opsYaml = opsMigrationRunner.ensureCurrentVersion(opsFile, CURRENT_OPS_SCHEMA_VERSION, logger)

        JsonSchemaValidator.validateData(dataYaml, fileName = dataFile.name)
        JsonSchemaValidator.validateOps(opsYaml, fileName = opsFile.name)

        val data: DataConfig = yamlMapper.readValue(dataYaml, DataConfig::class.java)
        val ops: OpsConfig = yamlMapper.readValue(opsYaml, OpsConfig::class.java)

        val crossResult = crossElementValidator.validate(data, ops)
        crossResult.warnings.forEach { logger.warn("[config] $it") }
        if (crossResult.errors.isNotEmpty()) {
            val details = crossResult.errors.joinToString(separator = "\n  - ", prefix = "  - ")
            throw MisconfigurationException(
                "Configuration failed cross-element validation:\n$details\n" +
                "Each item above identifies a configuration value to fix; re-run after correcting them."
            )
        }

        return ParsedConfiguration(data = data, ops = ops)
    }

    private fun requireExists(file: File) {
        if (!file.exists()) {
            throw MisconfigurationException(
                "Configuration file '${file.name}' does not exist at ${file.absolutePath}. " +
                "Provide a valid path or generate one from a template."
            )
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.gridgain.demo.datagen.config.ConfigurationParserTest'`
Expected: 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/ConfigurationParser.kt src/test/kotlin/com/gridgain/demo/datagen/config/ConfigurationParserTest.kt src/test/resources/data-v1-minimal.yaml src/test/resources/ops-v1-minimal.yaml
git commit -m "feat(datagen): add ConfigurationParser orchestrating the five-stage pipeline"
```

---

### Task 13: Full-suite green check

**Files:** none — verification only.

- [ ] **Step 1: Run the entire test suite**

Run: `./gradlew clean test`
Expected: `BUILD SUCCESSFUL`. 31 tests pass (Task 2: 3, Task 3: 3, Task 4: 3, Task 5: 4, Task 7: 6, Task 10: 5, Task 11: 2, Task 12: 5). All tests pass, none skipped.

- [ ] **Step 2: Inspect that no committed file references unimplemented types**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`. No compile errors.

- [ ] **Step 3: Final commit if anything was tweaked**

```bash
git status
# If clean, no commit needed.
```

---

## Verification (end-to-end smoke)

1. From the data-generator directory, copy the minimal fixtures into a temporary scratch dir and run the parser via a one-shot Kotlin scratch:

```kotlin
// scratch.kts
import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.slf4j.LoggerFactory
import java.io.File

val parser = ConfigurationParser(logger = Slf4jDataGenLogger(LoggerFactory.getLogger("scratch")))
val parsed = parser.parse(File("/tmp/data.yaml"), File("/tmp/ops.yaml"))
println("parsed: data=${parsed.data.schemaVersion} ops=${parsed.ops.schemaVersion}")
```

2. Smoke verify that `OutputLayout(Path("/tmp/output")).ensureBaseDirectories()` creates `/tmp/output/data-generator/{provisioning,state}` and that `RunId.generate()` produces a sortable identifier. (This is what Plan 3 will use to scaffold a run.)

3. Confirm `./gradlew clean test` is green from a fresh checkout.

---

## Spec Coverage Audit (this plan vs. the §s of the spec it claims to implement)

| Spec §                                                          | Covered by                              |
|-----------------------------------------------------------------|-----------------------------------------|
| §6 schema_version + migration runner                            | Tasks 6, 7                              |
| §6 JSONSchema location and pipeline                             | Tasks 9, 10                             |
| §6 validation pipeline migrate→JSONSchema→cross-element→deserialize | Task 12                             |
| §6 cross-element validation contract                            | Task 11 (rules added in Plans 2–4)      |
| §6 generated-output root + runs/<run-id>/                       | Task 5                                  |
| Project rule: rich error messages                               | Tasks 2, 7, 10, 11, 12                  |
| Project rule: no `org.gradle.*` in core                         | Whole plan — `com.gridgain.demo.datagen` package, no Gradle API |
| Project rule: SnakeYAML 1.33 forced                             | Task 1                                  |

**Out of scope of this plan (deferred to later plans):**
- Schema/column model, value-source plugins, cohort sampler (Plan 2).
- Scenario engine, business-event executor (Plan 3).
- KV targets, transaction wrapping (Plan 4).
- Provisioning emit/apply (Plan 5).
- State persistence implementation (Plan 6 — `OutputLayout.stateFile` already exists; serializer does not).
- OTel instrumentation (Plan 7).
- CLI entry point and plugin invocation (Plan 8).
