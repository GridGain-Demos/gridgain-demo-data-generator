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

data class ColumnSpec(
    val name: String,
    @JsonProperty("null_rate") val nullRate: Double,
    @JsonProperty("value_source") val valueSource: ValueSourceSpec,
)
