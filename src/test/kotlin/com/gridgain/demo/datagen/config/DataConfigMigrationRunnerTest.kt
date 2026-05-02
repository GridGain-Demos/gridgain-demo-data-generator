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
        assertThat(text).containsPattern("schemas:\\s*\\[\\s*\\]")
    }
}
