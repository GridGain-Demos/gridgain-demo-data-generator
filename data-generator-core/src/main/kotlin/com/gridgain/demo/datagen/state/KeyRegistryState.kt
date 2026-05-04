package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Per-schema list of keys emitted across all runs against this state file. Keys persist as
 * `List<String>` (JSON-safe); types beyond String round-trip via `Any.toString()`. F11 covers
 * the resulting type-fidelity gap when KeyRegistry replays loaded keys back into the runner.
 */
data class KeyRegistryState(
    @JsonProperty("schema_name") val schemaName: String,
    val keys: List<String>,
)
