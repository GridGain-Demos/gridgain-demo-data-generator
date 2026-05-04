package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Top-level state file shape. Persisted to `<demoOutputDirectory>/data-generator/state/state.yaml`
 * after each `runner.run()` by `StatePersister.save`. Loaded on next invocation by
 * `StatePersister.load` so sequence cursors and emitted keys carry over.
 *
 * `schemaVersion` is checked strictly — no migration. See `CURRENT_STATE_SCHEMA_VERSION`.
 */
data class GeneratorState(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val sequences: List<SequenceState>,
    val keys: List<KeyRegistryState>,
    @JsonProperty("run_history") val runHistory: List<RunHistoryEntry>,
)
