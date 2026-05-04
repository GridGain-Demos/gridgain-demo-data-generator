package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Single record in the run history index. `completedAt` is the only documented null case —
 * run aborted before `runner.run()` returns. Whitelisted per the "no nullable" project rule.
 */
data class RunHistoryEntry(
    @JsonProperty("run_id") val runId: String,
    @JsonProperty("scenario_name") val scenarioName: String,
    @JsonProperty("started_at") val startedAt: String,
    @JsonProperty("completed_at") val completedAt: String?,
    @JsonProperty("success_count") val successCount: Long,
    @JsonProperty("error_count") val errorCount: Long,
    @JsonProperty("stop_reason") val stopReason: String,
)
