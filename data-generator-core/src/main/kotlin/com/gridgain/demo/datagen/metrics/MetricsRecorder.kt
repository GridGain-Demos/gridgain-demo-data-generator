package com.gridgain.demo.datagen.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free cumulative counters fed by the scenario loop (one thread, [record] per op) and
 * read by the [LiveMetricsWriter] (another thread, [counters]). The writer derives per-interval
 * throughput and average latency by diffing successive [counters] reads — see
 * [LiveMetrics.computeSnapshot].
 *
 * The three counters are read independently, so a snapshot can be off by at most one in-flight
 * op's contribution; over a ~1s interval that skew is immaterial for a live gauge, and lock-free
 * reads keep the hot path (one [record] per generated op) free of contention.
 */
class MetricsRecorder {
    private val ops = AtomicLong(0)
    private val latencyNanos = AtomicLong(0)
    private val errors = AtomicLong(0)

    fun record(latencyNanos: Long, success: Boolean) {
        ops.incrementAndGet()
        this.latencyNanos.addAndGet(latencyNanos)
        if (!success) errors.incrementAndGet()
    }

    fun counters(): Counters = Counters(ops.get(), latencyNanos.get(), errors.get())

    /** Cumulative-since-construction snapshot of the raw counters. */
    data class Counters(val ops: Long, val latencyNanos: Long, val errors: Long)
}
