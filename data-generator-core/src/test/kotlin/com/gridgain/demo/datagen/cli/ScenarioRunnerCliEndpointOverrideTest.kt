package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import kotlin.test.Test

/**
 * Verifies F13's `--otel-endpoint-override` semantics: when the plugin supplies an
 * override (typically the deployed Prometheus/Grafana monitor's OTLP endpoint), it
 * wins over `ops.otel.endpoint` and forces NONE → OTLP.
 */
class ScenarioRunnerCliEndpointOverrideTest {
    private val logger: DataGenLogger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test fun `null override leaves ops_otel unchanged`() {
        val ops = OtelSpec(OtelExporter.OTLP, endpoint = "http://configured:4318")
        val effective = ScenarioRunnerCli.applyEndpointOverride(ops, override = null, logger)
        assertThat(effective).isSameAs(ops)
    }

    @Test fun `blank override leaves ops_otel unchanged`() {
        val ops = OtelSpec(OtelExporter.OTLP, endpoint = "http://configured:4318")
        val effective = ScenarioRunnerCli.applyEndpointOverride(ops, override = "   ", logger)
        assertThat(effective).isSameAs(ops)
    }

    @Test fun `override replaces ops_otel endpoint when exporter already otlp`() {
        val ops = OtelSpec(OtelExporter.OTLP, endpoint = "http://configured:4318")
        val effective = ScenarioRunnerCli.applyEndpointOverride(
            ops, override = "http://monitor:4318", logger,
        )
        assertThat(effective.exporter).isEqualTo(OtelExporter.OTLP)
        assertThat(effective.endpoint).isEqualTo("http://monitor:4318")
    }

    @Test fun `override on NONE exporter implicitly promotes to OTLP`() {
        val ops = OtelSpec(OtelExporter.NONE)
        val effective = ScenarioRunnerCli.applyEndpointOverride(
            ops, override = "http://monitor:4318", logger,
        )
        assertThat(effective.exporter).isEqualTo(OtelExporter.OTLP)
        assertThat(effective.endpoint).isEqualTo("http://monitor:4318")
    }

    @Test fun `override preserves ops_otel attributes`() {
        val ops = OtelSpec(
            OtelExporter.OTLP,
            endpoint = "http://configured:4318",
            attributes = mapOf("deployment.environment" to "dev"),
        )
        val effective = ScenarioRunnerCli.applyEndpointOverride(
            ops, override = "http://monitor:4318", logger,
        )
        assertThat(effective.attributes).containsEntry("deployment.environment", "dev")
    }

    @Test fun `override preserves prometheus exporter when configured that way`() {
        // Edge case: user opts into Prometheus mode locally; plugin supplies an
        // OTLP-style endpoint anyway. We don't auto-flip Prometheus to OTLP — the
        // user's explicit choice wins on exporter; only the address changes.
        val ops = OtelSpec(OtelExporter.PROMETHEUS, endpoint = "127.0.0.1:9464")
        val effective = ScenarioRunnerCli.applyEndpointOverride(
            ops, override = "http://monitor:4318", logger,
        )
        assertThat(effective.exporter).isEqualTo(OtelExporter.PROMETHEUS)
        assertThat(effective.endpoint).isEqualTo("http://monitor:4318")
    }
}
