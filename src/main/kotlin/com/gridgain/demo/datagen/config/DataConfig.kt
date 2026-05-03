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
)
