package com.gridgain.demo.datagen.scenario

import org.HdrHistogram.ConcurrentHistogram

/**
 * Operation latencies in nanoseconds, for the `latency_p99` / `latency_p999` stop conditions.
 *
 * Backed by an HdrHistogram rather than a list of samples, for two reasons:
 *
 * - **Bounded.** [StopConditionEvaluator] records into this on *every* operation, whether or not
 *   the scenario declares a latency stop condition. Retaining each sample individually cost
 *   roughly 1.4 GB per hour at 50k ops/s and grew with the rate, so the faster a run went the
 *   sooner it exhausted the heap — worst at exactly the throughput a load test is trying to reach.
 *   A histogram is flat in memory no matter how long the run lasts.
 * - **Thread-safe.** With `concurrency > 1` every worker thread records here at the full rate of
 *   the run; the growable list it replaced silently dropped elements under concurrent append.
 *
 * Sibling of [com.gridgain.demo.datagen.metrics.MetricsRecorder]'s histogram, which serves the
 * live metrics feed. They are kept separate because they answer to different configuration: this
 * one's precision is fixed by the stop-condition contract, that one's comes from `metrics:`.
 */
class LatencyHistogram {

    private val histogram = ConcurrentHistogram(HIGHEST_TRACKABLE_NANOS, SIGNIFICANT_DIGITS)

    /** How many samples have been recorded. */
    val count: Long get() = histogram.totalCount

    /**
     * Records one latency. Values beyond the tracking ceiling are clamped rather than rejected:
     * HdrHistogram throws on an out-of-range value, and aborting a run because one operation was
     * pathologically slow is strictly worse than a quantile pinned at the ceiling — which is the
     * honest reading of "slower than we can measure" in any case.
     */
    fun record(nanos: Long) {
        histogram.recordValue(nanos.coerceIn(0L, HIGHEST_TRACKABLE_NANOS))
    }

    /** The latency at quantile [p], or null when nothing has been recorded yet. */
    fun quantile(p: Double): Long? {
        require(p in 0.0..1.0) { "quantile p must be in [0, 1]; got $p" }
        if (histogram.totalCount == 0L) return null
        return histogram.getValueAtPercentile(p * 100.0)
    }

    private companion object {
        /** One hour. Any operation slower than this is pinned to the ceiling; see [record]. */
        const val HIGHEST_TRACKABLE_NANOS = 3_600_000_000_000L

        /**
         * Three digits of precision, matching the JSONSchema-recommended value for the live
         * metrics histogram, so a p99 read here and one read from the metrics feed agree.
         */
        const val SIGNIFICANT_DIGITS = 3
    }
}
