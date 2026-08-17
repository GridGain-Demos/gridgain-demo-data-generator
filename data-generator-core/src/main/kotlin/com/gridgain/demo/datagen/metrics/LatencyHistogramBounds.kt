package com.gridgain.demo.datagen.metrics

/**
 * Bounds of the whole-run latency histogram: the longest operation it can track and how precisely.
 *
 * Both come from the `metrics:` block of `ops.yaml` — see
 * [com.gridgain.demo.datagen.config.MetricsSpec]. There are no code-side defaults, per the
 * comprehensive-configuration-file policy; the recommended values live in the ops JSONSchema, which
 * is also what pre-fills the UI's form.
 *
 * Latencies are tracked in **microseconds**, not milliseconds or nanoseconds. Milliseconds would
 * throw away the sub-millisecond resolution a local run actually shows; nanoseconds would need a
 * range a thousand times wider for no gain, because three significant digits at nanosecond scale is
 * far finer than the clock. [highestTrackableMicros] converts the configured millisecond bound.
 */
data class LatencyHistogramBounds(
    /** Longest operation the histogram can record, in milliseconds. */
    val highestMs: Long,
    /** HdrHistogram precision, in significant decimal digits (3 => 0.1% error). */
    val significantDigits: Int,
) {
    init {
        require(highestMs > 0) {
            "metrics.histogram_highest_ms must be greater than 0; got $highestMs. This is the " +
                "longest single operation the run's latency histogram can record — set it above " +
                "the slowest operation you expect (60000, one minute, is the recommended value)."
        }
        require(significantDigits in 1..5) {
            "metrics.histogram_significant_digits must be between 1 and 5; got $significantDigits. " +
                "It is HdrHistogram's precision in significant decimal digits: 3 gives 0.1% error " +
                "and is the recommended value. Above 5 the histogram's memory grows for precision " +
                "no load test can use."
        }
    }

    /** The bound in the unit the histogram records in. */
    val highestTrackableMicros: Long get() = highestMs * 1_000L

    companion object {
        /**
         * Bounds for a recorder no consumer reads.
         *
         * [com.gridgain.demo.datagen.scenario.ScenarioRunner] defaults to a detached
         * [MetricsRecorder] so metrics collection stays opt-in by wiring a reporter to the same
         * instance. That recorder's histogram is never encoded or queried, so these are not a
         * configuration default standing in for a missing `metrics:` block — they are the shape of
         * an object with no reader. The production path always builds bounds from [MetricsSpec];
         * see `ScenarioRunnerCli`.
         */
        fun detached(): LatencyHistogramBounds =
            LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 3)
    }
}
