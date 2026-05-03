package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class OpsConfigMigrationRunnerTest {
    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test
    fun `migrates a v1 ops file forward to v2`(@TempDir dir: Path) {
        val file = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 2, logger = logger)
        assertThat(text).contains("schema_version: 2")
        assertThat(text).containsPattern("scenarios:\\s*\\[\\s*\\]")
    }
}
