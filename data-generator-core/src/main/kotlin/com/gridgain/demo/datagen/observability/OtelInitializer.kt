package com.gridgain.demo.datagen.observability

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource

/**
 * Builds an `OpenTelemetry` from an [OtelSpec]. Three modes:
 *
 * - **NONE**  — returns `OpenTelemetry.noop()`; no SDK is allocated.
 * - **OTLP**  — `OpenTelemetrySdk` with OTLP/HTTP metric + log exporters pointed at `endpoint`.
 * - **PROMETHEUS** — `OpenTelemetrySdk` with `PrometheusHttpServer` on `endpoint` (`host:port`).
 *
 * **Failure behaviour.** Any construction error (missing endpoint, unparseable host:port,
 * port already bound) is logged at WARN and returns `OpenTelemetry.noop()`. Production never
 * crashes on misconfigured OTel — that would be worse than running blind.
 *
 * **No GlobalOpenTelemetry registration.** Callers thread the returned instance explicitly so
 * tests can inject `InMemoryMetricReader` / `InMemoryLogRecordExporter` without race conditions
 * across parallel test classes.
 */
object OtelInitializer {

    fun fromSpec(spec: OtelSpec, logger: DataGenLogger): OpenTelemetry {
        return when (spec.exporter) {
            OtelExporter.NONE -> OpenTelemetry.noop()
            OtelExporter.OTLP -> buildOtlp(spec, logger) ?: OpenTelemetry.noop()
            OtelExporter.PROMETHEUS -> buildPrometheus(spec, logger) ?: OpenTelemetry.noop()
        }
    }

    fun close(otel: OpenTelemetry) {
        if (otel is OpenTelemetrySdk) otel.close()
    }

    private fun buildOtlp(spec: OtelSpec, logger: DataGenLogger): OpenTelemetrySdk? {
        val endpoint = spec.endpoint
        if (endpoint.isNullOrBlank()) {
            logger.warn("otel.exporter=otlp requires `endpoint` (e.g., http://collector:4318). " +
                "Falling back to OpenTelemetry.noop(); no metrics or logs will be exported.")
            return null
        }
        return try {
            val resource = buildResource(spec)
            val metricExporter = OtlpHttpMetricExporter.builder().setEndpoint("$endpoint/v1/metrics").build()
            val logExporter = OtlpHttpLogRecordExporter.builder().setEndpoint("$endpoint/v1/logs").build()
            OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().setResource(resource)
                    .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build()).build())
                .setLoggerProvider(SdkLoggerProvider.builder().setResource(resource)
                    .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build()).build())
                .build()
        } catch (e: Exception) {
            logger.warn("otel OTLP init failed: ${e.javaClass.simpleName}: ${e.message}; " +
                "falling back to OpenTelemetry.noop().")
            null
        }
    }

    private fun buildPrometheus(spec: OtelSpec, logger: DataGenLogger): OpenTelemetrySdk? {
        val raw = spec.endpoint ?: "0.0.0.0:9464"
        val (host, port) = try {
            val idx = raw.lastIndexOf(':'); require(idx > 0) { "expected host:port" }
            raw.substring(0, idx) to raw.substring(idx + 1).toInt()
        } catch (e: Exception) {
            logger.warn("otel.exporter=prometheus endpoint '$raw' is not host:port: ${e.message}; " +
                "falling back to noop.")
            return null
        }
        return try {
            val reader = PrometheusHttpServer.builder().setHost(host).setPort(port).build()
            OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().setResource(buildResource(spec))
                    .registerMetricReader(reader).build())
                .build()
        } catch (e: Exception) {
            logger.warn("otel Prometheus init failed: ${e.javaClass.simpleName}: ${e.message}; " +
                "falling back to OpenTelemetry.noop().")
            null
        }
    }

    /**
     * Builds the Resource attached to the SDK. The raw OTel Java SDK does not read
     * `OTEL_RESOURCE_ATTRIBUTES` itself (only the autoconfigure module does), so this
     * method parses it manually. Precedence — later wins:
     *   built-in service.name → ops.yaml `otel.attributes` → OTEL_RESOURCE_ATTRIBUTES env
     *
     * `internal` (not private) so tests can verify env-var parsing without standing up an
     * exporter. `envLookup` is the injection seam for tests; production passes `System::getenv`.
     */
    internal fun buildResource(
        spec: OtelSpec,
        envLookup: (String) -> String? = System::getenv,
    ): Resource {
        val builder = Attributes.builder()
            .put(AttributeKey.stringKey("service.name"), "gridgain-demo-data-generator")
        spec.attributes.forEach { (k, v) -> builder.put(AttributeKey.stringKey(k), v) }
        envLookup("OTEL_RESOURCE_ATTRIBUTES")?.let { value ->
            parseResourceAttributes(value).forEach { (k, v) ->
                builder.put(AttributeKey.stringKey(k), v)
            }
        }
        return Resource.getDefault().merge(Resource.create(builder.build()))
    }

    /**
     * Parses a comma-separated `k=v` list as defined by the OTel resource-attribute env
     * convention. Entries without a `=`, with an empty key, or empty after trimming are
     * silently dropped — a malformed entry should not block the rest of the resource.
     */
    internal fun parseResourceAttributes(value: String): Map<String, String> =
        value.split(",").mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].trim()
            val v = parts[1].trim()
            if (k.isEmpty()) null else k to v
        }.toMap()
}
