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
    fun `auto-migrates a v1 data file forward to v2 end-to-end`(@TempDir dir: Path) {
        val data = copyResource(dir, "data-v1-minimal.yaml", "data.yaml")
        val ops = copyResource(dir, "ops-v1-minimal.yaml", "ops.yaml")
        val parser = ConfigurationParser(logger = logger)
        val parsed = parser.parse(dataFile = data.toFile(), opsFile = ops.toFile())
        assertThat(parsed.data.schemaVersion).isEqualTo(2)
        assertThat(parsed.data.schemas).isEmpty()
        assertThat(parsed.ops.schemaVersion).isEqualTo(2)
        assertThat(parsed.ops.scenarios).isEmpty()
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
        assertThat(parsed.data.schemaVersion).isEqualTo(2)
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

    @Test
    fun `missing ops file is rejected with remediation`(@TempDir dir: Path) {
        val data = dir.resolve("data.yaml").also { it.writeText("schema_version: 1\n") }
        val parser = ConfigurationParser(logger = logger)
        assertThatThrownBy { parser.parse(dataFile = data.toFile(), opsFile = dir.resolve("missing-ops.yaml").toFile()) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("missing-ops.yaml")
            .hasMessageContaining("does not exist")
    }
}
