package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * v1 envelope for data.yaml. The body is intentionally permissive — Plans 2 and beyond
 * tighten the schema and add typed fields. The body map preserves any extra keys the user
 * supplies, so this v1 envelope can read forward-versioned files for inspection (validation
 * is enforced separately by JsonSchemaValidator).
 */
data class DataConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    @get:JsonAnyGetter val body: MutableMap<String, Any?> = mutableMapOf()
) {
    @JsonAnySetter
    @JsonIgnore
    fun put(key: String, value: Any?) { body[key] = value }
}
