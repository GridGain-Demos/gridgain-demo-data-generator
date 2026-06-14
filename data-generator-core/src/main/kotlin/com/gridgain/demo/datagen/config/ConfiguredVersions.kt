package com.gridgain.demo.datagen.config

const val CURRENT_DATA_SCHEMA_VERSION: Int = 2
const val CURRENT_OPS_SCHEMA_VERSION: Int = 4

/**
 * State file ("`<demoOutputDirectory>/data-generator/state/state.yaml`") schema version.
 * **No migration support.** A mismatched `schemaVersion` in `state.yaml` is a hard error:
 * `StatePersister.load` throws `CorruptedStateException` with remediation guidance to tear
 * down and re-run from clean state. Mirrors the plugin's `deployment.yaml` policy.
 *
 * v1 → v2: F11 closure adds the `key_type` discriminator on `KeyRegistryState` so loaded
 * keys round-trip with their original runtime type (Long-keyed caches no longer get sampled
 * with `String("1")` after a restart). v1 state files lack `key_type` and fail-load by
 * design.
 */
const val CURRENT_STATE_SCHEMA_VERSION: Int = 2
