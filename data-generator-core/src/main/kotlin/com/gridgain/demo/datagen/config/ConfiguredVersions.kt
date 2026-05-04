package com.gridgain.demo.datagen.config

const val CURRENT_DATA_SCHEMA_VERSION: Int = 2
const val CURRENT_OPS_SCHEMA_VERSION: Int = 2

/**
 * State file ("`<demoOutputDirectory>/data-generator/state/state.yaml`") schema version.
 * **No migration support.** A mismatched `schemaVersion` in `state.yaml` is a hard error:
 * `StatePersister.load` throws `CorruptedStateException` with remediation guidance to tear
 * down and re-run from clean state. Mirrors the plugin's `deployment.yaml` policy.
 */
const val CURRENT_STATE_SCHEMA_VERSION: Int = 1
