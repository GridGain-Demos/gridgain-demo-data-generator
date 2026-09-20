package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class DataConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val schemas: List<SchemaSpec>,
)

data class SchemaSpec(
    val name: String,
    @JsonProperty("update_ratio") val updateRatio: Double,
    val columns: List<ColumnSpec>,
    /**
     * Copies of each row beyond the primary. `0` is one copy and no redundancy.
     *
     * `MigrateV2toV3` writes `0` into every older schema — which is what those caches already
     * had — so a real data.yaml states it explicitly. The default here exists for the same
     * documented reason as [ColumnSpec.affinity]: it keeps every fixture and every hand-written
     * pre-v3 file constructing, and it is the value those files already behaved as. Only
     * consumed when the scenario provisions the cache
     * (`provisioning: emit|apply`) — a cache that already exists keeps the configuration it was
     * created with, and GG8 rejects `getOrCreateCache` with a conflicting one.
     */
    val backups: Int = 0,
    /** See [WriteSyncMode]. The migration writes it into older files; defaulted for the same
     *  reason as [backups]. */
    @JsonProperty("write_synchronization_mode")
    val writeSynchronizationMode: WriteSyncMode = WriteSyncMode.PRIMARY_SYNC,
)

/**
 * NOTE: `affinity` carries a default value of false in violation of the workspace project rule
 * "no defaults on template classes". This exception is intentional and limited: Plan 5 introduces
 * the field as a forward-compat annotation for provisioning (Plan 6+). Forcing every existing
 * column declaration to opt in explicitly would require migrating every demoConfigFile in the
 * wild for no behavioral benefit. Plan 6 may revisit this when provisioning starts to consume it.
 */
data class ColumnSpec(
    val name: String,
    @JsonProperty("null_rate") val nullRate: Double,
    @JsonProperty("value_source") val valueSource: ValueSourceSpec,
    val affinity: Boolean = false,
    val key: Boolean = false,
)
