package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class GeneratorStateRoundTripTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test fun `round-trips through yaml with snake_case`() {
        val original = GeneratorState(
            schemaVersion = CURRENT_STATE_SCHEMA_VERSION,
            sequences = listOf(SequenceState("customer", "id", 4201)),
            keys = listOf(KeyRegistryState("customer", listOf("1", "2", "3"))),
            runHistory = listOf(RunHistoryEntry(
                runId = "20260504-091215-x9k3pa",
                scenarioName = "customer-load",
                startedAt = "2026-05-04T09:12:15Z",
                completedAt = "2026-05-04T09:12:23Z",
                successCount = 200, errorCount = 0, stopReason = "count reached",
            )),
        )
        val yaml = mapper.writeValueAsString(original)
        assertThat(yaml).contains("schema_version: 1", "schema_name: \"customer\"",
                                 "column_name: \"id\"", "next_value: 4201",
                                 "run_id:", "started_at:", "stop_reason:")
        assertThat(mapper.readValue(yaml, GeneratorState::class.java)).isEqualTo(original)
    }
}
