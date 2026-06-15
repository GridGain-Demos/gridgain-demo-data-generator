package com.gridgain.demo.datagen.metrics

/**
 * A point-in-time view of generator throughput and per-operation execution latency,
 * written ~once per second to a small JSON file ([LiveMetricsWriter]) so external
 * consumers (e.g. a demo UI) can poll a stable path without any metrics infrastructure.
 *
 * [observedTps] and [avgLatencyMs] are *interval* rates (derived from the delta between two
 * cumulative [MetricsRecorder] reads), not lifetime averages — that's what makes a live
 * graph track the current load rather than smoothing over the whole run. Latency is the
 * wall time the generator spent executing each operation against the target sink (e.g. the
 * GridGain write), which is the "GridGain execution time" a load test cares about.
 */
data class MetricsSnapshot(
    val updatedAtMs: Long,
    val observedTps: Double,
    val avgLatencyMs: Double,
    val totalOps: Long,
    val errorCount: Long,
    val targetTps: Double,
    val runId: String,
    /** False in the final snapshot written at run end, so consumers zero out between runs. */
    val active: Boolean,
)
