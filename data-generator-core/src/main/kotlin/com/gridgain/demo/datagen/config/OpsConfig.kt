package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * NOTE: `targets` carries a default value of `emptyList()` in violation of the workspace project
 * rule "no defaults on template classes". This exception is intentional and limited: Plan 6
 * introduces the field as a forward-compat addition, and forcing every existing
 * `OpsConfig(...)` call site (especially in tests) to opt in explicitly would be a sweeping change
 * for no behavioral benefit. The cross-element validator enforces target presence at runtime where
 * it matters (scenarios with read_ratio > 0 or business_event transactions).
 */
data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val targets: List<TargetSpec> = emptyList(),
    val otel: OtelSpec = OtelSpec.NONE,
    val scenarios: List<ScenarioSpec>,
)
