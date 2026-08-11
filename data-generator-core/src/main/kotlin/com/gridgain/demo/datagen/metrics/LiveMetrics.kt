package com.gridgain.demo.datagen.metrics

/** Pure derivation of a [MetricsSnapshot] from two cumulative counter reads. Kept separate from
 *  [LiveMetricsReporter] (which owns the clock, thread, and transport) so the rate/latency math is
 *  deterministically unit-testable. */
object LiveMetrics {

    fun computeSnapshot(
        prev: MetricsRecorder.Counters,
        cur: MetricsRecorder.Counters,
        intervalNanos: Long,
        targetTps: Double,
        runGroup: String,
        runId: String,
        nowMs: Long,
        active: Boolean,
    ): MetricsSnapshot {
        val deltaOps = cur.ops - prev.ops
        val deltaLatencyNanos = cur.latencyNanos - prev.latencyNanos
        val observedTps =
            if (intervalNanos > 0) deltaOps.toDouble() / (intervalNanos / 1_000_000_000.0) else 0.0
        val avgLatencyMs =
            if (deltaOps > 0) (deltaLatencyNanos.toDouble() / deltaOps) / 1_000_000.0 else 0.0
        return MetricsSnapshot(
            updatedAtMs = nowMs,
            observedTps = observedTps,
            avgLatencyMs = avgLatencyMs,
            totalOps = cur.ops,
            errorCount = cur.errors,
            targetTps = targetTps,
            runGroup = runGroup,
            runId = runId,
            active = active,
        )
    }
}
