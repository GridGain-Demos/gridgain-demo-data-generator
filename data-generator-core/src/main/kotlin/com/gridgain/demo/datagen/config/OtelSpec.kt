package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Top-level `otel:` block in ops.yaml. Default is `OtelSpec.NONE` (exporter = NONE);
 * `OtelInitializer.fromSpec` translates that to `OpenTelemetry.noop()` and every
 * instrument call site degrades to a no-op without raising.
 *
 * NOTE: `attributes` defaults to `emptyMap()` and `endpoint` is nullable in
 * violation of the workspace project rules ("no defaults on template classes" /
 * "no nullable types"). Both are intentional and case-by-case approved:
 *   - `endpoint` is genuinely optional (`exporter: none` ignores it; `prometheus`
 *     defaults to `0.0.0.0:9464` inside `OtelInitializer` if absent).
 *   - `attributes` is an additive resource-attribute bag; forcing every ops.yaml
 *     to spell out an empty map would just be noise.
 * Documented exception, mirroring `ScenarioSpec.transactionScope` / `OpsConfig.targets`.
 */
data class OtelSpec(
    val exporter: OtelExporter,
    val endpoint: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object { val NONE: OtelSpec = OtelSpec(OtelExporter.NONE) }
}

enum class OtelExporter {
    @JsonProperty("none") NONE,
    @JsonProperty("otlp") OTLP,
    @JsonProperty("prometheus") PROMETHEUS,
}
