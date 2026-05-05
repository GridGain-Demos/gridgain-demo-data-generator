package com.gridgain.demo.datagen.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import java.util.concurrent.atomic.AtomicReference

/**
 * Single registration point for every OTel instrument the generator emits.
 * Spec §7 Extensibility: the instrument list lives here, instrument names are
 * not chosen at call sites. Adding a new instrument is a one-file change here.
 *
 * **No-op safety.** When constructed with `OpenTelemetry.noop()` (the
 * `exporter: none` case) every instrument is the SDK's no-op and records nothing.
 * Production paths never crash on misconfigured OTel — `OtelInitializer` logs
 * the failure once and returns the noop SDK.
 *
 * **Gauge contract.** Asynchronous gauges poll an `AtomicReference<Double>` slot.
 * Callers set values on `targetRateRef` / `observedRateRef`; the SDK reads them
 * on its own collection schedule. A null value (initial state) is reported as 0.0.
 */
class Instruments(otel: OpenTelemetry) {

    companion object {
        const val OP_LATENCY = "data_generator.op.latency"
        const val OP_COUNT = "data_generator.op.count"
        const val OP_ERRORS = "data_generator.op.errors"
        const val IN_FLIGHT = "data_generator.in_flight"
        const val TARGET_RATE = "data_generator.target_rate"
        const val OBSERVED_RATE = "data_generator.observed_rate"

        const val ATTR_SCENARIO = "scenario"
        const val ATTR_TARGET = "target"
        const val ATTR_SCHEMA = "schema"
        const val ATTR_OP = "op"            // put | get | tx_commit | tx_rollback
        const val ATTR_EXCEPTION = "exception"

        /** Convenience for the default scope name across all data-generator instruments. */
        const val SCOPE = "com.gridgain.demo.datagen"

        /** Returns an `Instruments` backed by `OpenTelemetry.noop()` — the production fallback. */
        fun noop(): Instruments = Instruments(OpenTelemetry.noop())
    }

    private val meter: Meter = otel.meterBuilder(SCOPE).build()

    val opLatency: DoubleHistogram = meter.histogramBuilder(OP_LATENCY)
        .setDescription("End-to-end latency of one target op.").setUnit("ns").build()
    val opCount: LongCounter = meter.counterBuilder(OP_COUNT)
        .setDescription("Count of completed target ops.").build()
    val opErrors: LongCounter = meter.counterBuilder(OP_ERRORS)
        .setDescription("Count of failed target ops, tagged by exception class.").build()
    val inFlight: LongUpDownCounter = meter.upDownCounterBuilder(IN_FLIGHT)
        .setDescription("In-flight target ops at this instant.").build()

    val targetRateRef: AtomicReference<Double> = AtomicReference(0.0)
    val observedRateRef: AtomicReference<Double> = AtomicReference(0.0)

    @Suppress("unused") val targetRate: ObservableDoubleGauge = meter.gaugeBuilder(TARGET_RATE)
        .setDescription("Configured target ops/sec (read once at run start).")
        .buildWithCallback { it.record(targetRateRef.get() ?: 0.0) }
    @Suppress("unused") val observedRate: ObservableDoubleGauge = meter.gaugeBuilder(OBSERVED_RATE)
        .setDescription("Achieved ops/sec since run start.")
        .buildWithCallback { it.record(observedRateRef.get() ?: 0.0) }

    /** Builds the `Attributes` for one `tick()` op. Centralised so call sites don't reinvent attr keys. */
    fun opAttributes(scenario: String, target: String, schema: String, op: String): Attributes =
        Attributes.builder().put(ATTR_SCENARIO, scenario).put(ATTR_TARGET, target)
            .put(ATTR_SCHEMA, schema).put(ATTR_OP, op).build()
}
