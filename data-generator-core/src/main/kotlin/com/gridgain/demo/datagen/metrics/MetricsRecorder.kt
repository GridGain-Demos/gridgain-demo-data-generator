package com.gridgain.demo.datagen.metrics

import java.util.concurrent.atomic.AtomicLong
import org.HdrHistogram.ConcurrentHistogram
import org.HdrHistogram.Histogram

/**
 * Lock-free cumulative counters plus a whole-run latency histogram, fed by the scenario's worker
 * threads ([record] per op, one caller per worker — a scenario's `concurrency` sets how many) and
 * read by the [LiveMetricsReporter] (another thread again, [counters] and [histogramSnapshot]).
 * The reporter derives per-interval throughput and average latency by diffing successive
 * [counters] reads — see [LiveMetrics.computeSnapshot].
 *
 * The three counters are read independently, so a snapshot can be off by at most one in-flight op's
 * contribution; over a ~1s interval that skew is immaterial for a live gauge, and lock-free reads
 * keep the hot path (one [record] per generated op) free of contention.
 *
 * ### Why a histogram as well as the counters
 * The counters can only ever yield means. This is the only component that observes every individual
 * operation latency, so it is the only one that can compute a defensible p90 — an interval mean
 * smooths the tail away almost entirely. [ConcurrentHistogram] is used rather than
 * [com.gridgain.demo.datagen.scenario.LatencyHistogram] because the latter retains every sample in
 * an unbounded list (roughly 1.4 GB for an hour at 50k ops/s); this one is bounded by [bounds],
 * safe for concurrent recording, and has a standard compressed encoding for the wire.
 *
 * ### [isDetached]
 * True only for a recorder built via [detached] — never inferred from [bounds] having the same
 * values [LatencyHistogramBounds.detached] happens to produce, because those are also the
 * JSONSchema-recommended values, and a real `metrics:` block using them must not be mistaken for
 * the sentinel. Nothing in this class reads the flag; it exists so that a caller wiring a
 * [LiveMetricsReporter] to a recorder, or encoding its [histogramSnapshot], can
 * `require(!isDetached)` first, catching a future refactor that attaches a sink to the no-op
 * default instead of a configured recorder. That guard belongs at the wiring call site, not here.
 */
class MetricsRecorder private constructor(
    private val bounds: LatencyHistogramBounds,
    internal val isDetached: Boolean,
) {
    constructor(bounds: LatencyHistogramBounds) : this(bounds, isDetached = false)

    private val ops = AtomicLong(0)
    private val latencyNanos = AtomicLong(0)
    private val errors = AtomicLong(0)

    private val histogram = ConcurrentHistogram(
        bounds.highestTrackableMicros,
        bounds.significantDigits,
    )

    /**
     * The post-warmup window only — a **second** histogram rather than a reset of the first.
     *
     * The whole-run histogram feeds the live Kafka snapshot, whose consumers merge it across a fleet
     * and expect it to cover the run. Resetting it at the warmup boundary would silently change that
     * contract and make a mid-run graph disagree with itself. Two histograms cost one bounded
     * allocation and one extra record per operation, which is nothing beside the round trip being
     * measured, and keep the two questions — "what is happening now" and "what does this run
     * report" — genuinely separate.
     */
    private val measured = ConcurrentHistogram(
        bounds.highestTrackableMicros,
        bounds.significantDigits,
    )

    /** False until [beginMeasurement]; a warmup operation is recorded in [histogram] alone. */
    @Volatile private var measuring = false

    /**
     * Close the warmup window: from here on, operations also enter the measured histogram.
     *
     * Idempotent, and safe to call from whichever worker observes the boundary first.
     */
    fun beginMeasurement() {
        measuring = true
    }

    fun record(latencyNanos: Long, success: Boolean) {
        ops.incrementAndGet()
        this.latencyNanos.addAndGet(latencyNanos)
        if (!success) errors.incrementAndGet()
        // Clamped, not dropped and not allowed to throw. HdrHistogram rejects a value above the
        // configured bound (or below zero), and killing a generator run because one operation was
        // unusually slow is strictly worse than a p99 pinned at the ceiling — which is the honest
        // reading of "slower than the configured bound can measure" anyway.
        val micros = (latencyNanos / 1_000L).coerceIn(0L, bounds.highestTrackableMicros)
        histogram.recordValue(micros)
        if (measuring) measured.recordValue(micros)
    }

    fun counters(): Counters = Counters(ops.get(), latencyNanos.get(), errors.get())

    /**
     * A stable copy of the whole-run histogram, in **microseconds**.
     *
     * A copy rather than the live instance: the reporter encodes this on its own thread while
     * workers keep recording, and an encode of the live histogram could observe a torn state.
     */
    fun histogramSnapshot(): Histogram = histogram.copy()

    /**
     * A stable copy of the **measured window** — everything after [beginMeasurement] — in
     * microseconds. This is what `result.yaml` reports, so a run's quoted percentiles describe the
     * cluster rather than the JVM's first thousand operations.
     */
    fun measuredSnapshot(): Histogram = measured.copy()

    /** Cumulative-since-construction snapshot of the raw counters. */
    data class Counters(val ops: Long, val latencyNanos: Long, val errors: Long)

    companion object {
        /**
         * A recorder **no reporter publishes** — the default
         * [com.gridgain.demo.datagen.scenario.ScenarioRunner] uses so that *publishing* metrics
         * stays opt-in by wiring a reporter to the same instance.
         *
         * Detached is not inert. It records exactly as a configured recorder does, and the run reads
         * its histogram back for `result.yaml`'s percentiles — so a run with no `metrics:` block
         * still reports its own latency. See [LatencyHistogramBounds.detached] for why these bounds
         * are not a configuration default, and [isDetached] for how a wiring call site can tell this
         * instance apart from a configured one.
         */
        fun detached(): MetricsRecorder =
            MetricsRecorder(LatencyHistogramBounds.detached(), isDetached = true)
    }
}
