package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val otel: OtelSpec = OtelSpec.NONE,
    // Optional live-metrics export (v4+). Null when `metrics:` is omitted — consistent with the
    // otel block being optional; the reporter is only wired when this is present.
    val metrics: MetricsSpec? = null,
    // Optional runtime control channel (v5+). Null when `control:` is omitted — the configured
    // rate schedule then paces the whole run and no listener is wired.
    val control: ControlSpec? = null,
    val scenarios: List<ScenarioSpec>,
)
