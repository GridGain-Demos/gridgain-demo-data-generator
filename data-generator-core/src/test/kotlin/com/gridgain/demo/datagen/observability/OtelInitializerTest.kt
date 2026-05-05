package com.gridgain.demo.datagen.observability

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import kotlin.test.Test

class OtelInitializerTest {
    private val logger: DataGenLogger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test fun `none returns OpenTelemetry-noop`() {
        val otel = OtelInitializer.fromSpec(OtelSpec(OtelExporter.NONE), logger)
        assertThat(otel).isSameAs(OpenTelemetry.noop())
    }

    @Test fun `otlp and prometheus build a closeable OpenTelemetrySdk`() {
        // Prometheus binds 127.0.0.1:0 so the OS picks an unused port.
        listOf(
            OtelSpec(OtelExporter.OTLP, endpoint = "http://localhost:4318"),
            OtelSpec(OtelExporter.PROMETHEUS, endpoint = "127.0.0.1:0"),
        ).forEach { spec ->
            val otel = OtelInitializer.fromSpec(spec, logger)
            assertThat(otel).isInstanceOf(OpenTelemetrySdk::class.java)
            OtelInitializer.close(otel)
        }
    }

    @Test fun `misconfigured exporters fall back to noop with a warning`() {
        // OTLP without endpoint, Prometheus with garbage host:port — both must noop.
        assertThat(OtelInitializer.fromSpec(OtelSpec(OtelExporter.OTLP, endpoint = null), logger))
            .isSameAs(OpenTelemetry.noop())
        assertThat(OtelInitializer.fromSpec(
            OtelSpec(OtelExporter.PROMETHEUS, endpoint = "not-a-host-port"), logger,
        )).isSameAs(OpenTelemetry.noop())
    }
}
