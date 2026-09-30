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
        /**
         * Reads that succeeded and found nothing.
         *
         * Separate from [OP_ERRORS] because a miss is not a failure. Its value is as a ratio against
         * `op=get` in [OP_COUNT]: a read-heavy run whose miss ratio is near 1 is measuring the
         * cluster's empty path, and its throughput figure means nothing.
         */
        const val OP_MISSES = "data_generator.op.misses"
        const val IN_FLIGHT = "data_generator.in_flight"
        const val TARGET_RATE = "data_generator.target_rate"
        const val OBSERVED_RATE = "data_generator.observed_rate"

        const val ATTR_SCENARIO = "scenario"
        /**
         * The target cluster. Since ops v7 this carries the **cluster name** (from
         * `--target-cluster` / [com.gridgain.demo.datagen.cli.ScenarioRunnerCli.Resolution.targetClusterName])
         * — before v7 it carried the alias of a `targets[]` entry. The attribute name is unchanged
         * so existing queries keep resolving; a dashboard that groups by it will re-label rather
         * than empty.
         */
        const val ATTR_TARGET = "target"
        const val ATTR_SCHEMA = "schema"
        const val ATTR_OP = "op"            // put | get | tx_commit | tx_rollback
        const val ATTR_EXCEPTION = "exception"
        /** Per-tick partition id when running in distributed mode; absent in single-pod runs. */
        const val ATTR_PARTITION = "partition"

        // Coordinator-only metric names. Reserved here so the naming is documented in one
        // place; the actual instruments are registered by the Coordinator class only while
        // a pod holds the leader lease.
        const val COORDINATOR_WORKER_COUNT = "data_generator.coordinator.worker_count"
        const val COORDINATOR_PARTITION_ASSIGNMENTS = "data_generator.coordinator.partition_assignments"
        const val COORDINATOR_REBALANCES = "data_generator.coordinator.rebalances"

        /** Convenience for the default scope name across all data-generator instruments. */
        const val SCOPE = "com.gridgain.demo.datagen"

        /**
         * Explicit bucket boundaries for [OP_LATENCY], in **nanoseconds**.
         *
         * Without these the SDK applies its default boundaries, which end at 10,000 — a sensible
         * ceiling for a metric measured in milliseconds and a catastrophic one for a metric measured
         * in nanoseconds, where it means 10 microseconds. Every operation the generator has ever
         * performed therefore landed in the `+Inf` bucket, and `histogram_quantile` over
         * `data_generator_op_latency_nanoseconds_bucket` had nothing finite to interpolate between.
         * The series was emitted, scraped and stored for the whole life of the generator, and was
         * never able to answer the question it exists to answer.
         *
         * The unit stays nanoseconds rather than moving to milliseconds on purpose. Prometheus names
         * the series after its unit, so switching would rename it and silently empty every existing
         * dashboard panel and recording rule rather than fix them.
         *
         * Roughly logarithmic from 10 µs to 10 s: dense where a cache hit lives, sparse across the
         * tail where only the order of magnitude matters.
         */
        val LATENCY_BUCKETS_NANOS: List<Double> = listOf(
            10_000.0,          //  10 µs
            25_000.0,          //  25 µs
            50_000.0,          //  50 µs
            100_000.0,         // 100 µs
            250_000.0,         // 250 µs
            500_000.0,         // 500 µs
            1_000_000.0,       //   1 ms
            2_500_000.0,       // 2.5 ms
            5_000_000.0,       //   5 ms
            10_000_000.0,      //  10 ms
            25_000_000.0,      //  25 ms
            50_000_000.0,      //  50 ms
            100_000_000.0,     // 100 ms
            250_000_000.0,     // 250 ms
            500_000_000.0,     // 500 ms
            1_000_000_000.0,   //   1 s
            5_000_000_000.0,   //   5 s
            10_000_000_000.0,  //  10 s
        )

        /** Returns an `Instruments` backed by `OpenTelemetry.noop()` — the production fallback. */
        fun noop(): Instruments = Instruments(OpenTelemetry.noop())
    }

    private val meter: Meter = otel.meterBuilder(SCOPE).build()

    val opLatency: DoubleHistogram = meter.histogramBuilder(OP_LATENCY)
        .setDescription("End-to-end latency of one target op.").setUnit("ns")
        .setExplicitBucketBoundariesAdvice(LATENCY_BUCKETS_NANOS)
        .build()
    val opCount: LongCounter = meter.counterBuilder(OP_COUNT)
        .setDescription("Count of completed target ops.").build()
    val opErrors: LongCounter = meter.counterBuilder(OP_ERRORS)
        .setDescription("Count of failed target ops, tagged by exception class.").build()
    val opMisses: LongCounter = meter.counterBuilder(OP_MISSES)
        .setDescription("Count of reads that succeeded and found no value.").build()
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

    /**
     * Returns [base] decorated with the partition attribute. Used by worker code in
     * distributed mode to tag every per-tick measurement with its slice id; single-pod
     * runs never call this and stay schema-compatible with their existing attribute set.
     */
    fun withPartition(base: Attributes, partition: Int): Attributes =
        base.toBuilder().put(ATTR_PARTITION, partition.toLong()).build()
}
