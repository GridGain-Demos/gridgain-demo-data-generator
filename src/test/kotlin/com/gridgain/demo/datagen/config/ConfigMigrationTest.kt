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

    private fun writeYaml(dir: Path, name: String, body: String): Path {
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
