package com.gridgain.demo.datagen.observability

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
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

    // OTEL_RESOURCE_ATTRIBUTES env var must flow into the Resource. The raw OTel
    // Java SDK (without autoconfigure) does not read this env var, so OtelInitializer
    // parses it manually.

    @Test fun `buildResource carries attributes parsed from OTEL_RESOURCE_ATTRIBUTES env var`() {
        val envValue = "service.instance.id=pod-x," +
            "k8s.pod.name=pod-x," +
            "k8s.namespace.name=td-8a-servers," +
            "gridgain.demo.cluster=example-gcp-8a," +
            "gridgain.demo.scenario=gg8-verify"
        val resource = OtelInitializer.buildResource(OtelSpec(OtelExporter.NONE)) { name ->
            if (name == "OTEL_RESOURCE_ATTRIBUTES") envValue else null
        }
        val attrs = resource.attributes
        assertThat(attrs.get(AttributeKey.stringKey("service.instance.id"))).isEqualTo("pod-x")
        assertThat(attrs.get(AttributeKey.stringKey("k8s.pod.name"))).isEqualTo("pod-x")
        assertThat(attrs.get(AttributeKey.stringKey("k8s.namespace.name"))).isEqualTo("td-8a-servers")
        assertThat(attrs.get(AttributeKey.stringKey("gridgain.demo.cluster"))).isEqualTo("example-gcp-8a")
        assertThat(attrs.get(AttributeKey.stringKey("gridgain.demo.scenario"))).isEqualTo("gg8-verify")
    }

    @Test fun `buildResource preserves built-in service-name when OTEL_RESOURCE_ATTRIBUTES is absent`() {
        val resource = OtelInitializer.buildResource(OtelSpec(OtelExporter.NONE)) { null }
        assertThat(resource.attributes.get(AttributeKey.stringKey("service.name")))
            .isEqualTo("gridgain-demo-data-generator")
    }

    @Test fun `buildResource skips malformed entries in OTEL_RESOURCE_ATTRIBUTES`() {
        // Entries without `=`, with empty keys, or stray whitespace must not break parsing.
        val resource = OtelInitializer.buildResource(OtelSpec(OtelExporter.NONE)) { name ->
            if (name == "OTEL_RESOURCE_ATTRIBUTES")
                "bad-entry-no-equals,=missing-key,, good.key = good-value "
            else null
        }
        assertThat(resource.attributes.get(AttributeKey.stringKey("good.key"))).isEqualTo("good-value")
        assertThat(resource.attributes.get(AttributeKey.stringKey(""))).isNull()
        assertThat(resource.attributes.get(AttributeKey.stringKey("bad-entry-no-equals"))).isNull()
    }
}
