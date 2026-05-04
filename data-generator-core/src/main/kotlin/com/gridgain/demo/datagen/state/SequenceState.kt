package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.annotation.JsonProperty

data class SequenceState(
    @JsonProperty("schema_name") val schemaName: String,
    @JsonProperty("column_name") val columnName: String,
    @JsonProperty("next_value") val nextValue: Long,
)
