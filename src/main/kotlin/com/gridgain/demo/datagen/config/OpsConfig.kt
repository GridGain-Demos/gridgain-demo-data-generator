package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * v1 envelope for ops.yaml. Same permissive design as DataConfig.
 */
data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    @get:JsonAnyGetter val body: MutableMap<String, Any?> = mutableMapOf()
) {
    @JsonAnySetter
    @JsonIgnore
    fun put(key: String, value: Any?) { body[key] = value }
}
