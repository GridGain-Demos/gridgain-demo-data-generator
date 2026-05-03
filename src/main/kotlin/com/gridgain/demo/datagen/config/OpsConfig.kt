package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val scenarios: List<ScenarioSpec>,
)
