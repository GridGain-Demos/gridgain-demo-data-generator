package com.gridgain.demo.datagen.scenario

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class ScenarioResult(
    val scenarioName: String,
    val achievedRate: Double,
    val errorCount: Long,
    val successCount: Long,
    val stopReason: String,
    val wallTime: Duration,
) {
    companion object {
        private val mapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

        fun write(result: ScenarioResult, path: Path) {
            Files.createDirectories(path.parent)
            val map = linkedMapOf(
                "scenario_name" to result.scenarioName,
                "achieved_rate" to result.achievedRate,
                "error_count" to result.errorCount,
                "success_count" to result.successCount,
                "stop_reason" to result.stopReason,
                "wall_time" to result.wallTime.toString(),
            )
            mapper.writeValue(path.toFile(), map)
        }
    }
}
