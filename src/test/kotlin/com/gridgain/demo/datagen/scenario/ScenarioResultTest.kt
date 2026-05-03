package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

class ScenarioResultTest {

    @Test
    fun `writes a yaml file under run directory`(@TempDir dir: Path) {
        val result = ScenarioResult(
            scenarioName = "alpha",
            achievedRate = 95.4,
            errorCount = 3,
            successCount = 1000,
            stopReason = "duration elapsed",
            wallTime = Duration.ofSeconds(10),
        )
        val out = dir.resolve("result.yaml")
        ScenarioResult.write(result, out)
        val text = Files.readString(out)
        assertThat(text).contains("scenario_name: \"alpha\"")
        assertThat(text).contains("achieved_rate: 95.4")
        assertThat(text).contains("error_count: 3")
        assertThat(text).contains("success_count: 1000")
        assertThat(text).contains("stop_reason: \"duration elapsed\"")
        assertThat(text).contains("wall_time: \"PT10S\"")
    }
}
