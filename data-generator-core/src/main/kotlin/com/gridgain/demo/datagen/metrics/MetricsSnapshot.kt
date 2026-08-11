package com.gridgain.demo.datagen.metrics

/**
 * A point-in-time view of generator throughput and per-operation execution latency, published
 * ~once per second by [LiveMetricsReporter] over a [MetricsSink] (in the demo, a Kafka topic) so
 * external consumers such as the demo UI can render live graphs without any metrics infrastructure.
 *
 * [observedTps] and [avgLatencyMs] are *interval* rates (derived from the delta between two
 * cumulative [MetricsRecorder] reads), not lifetime averages — that's what makes a live graph track
 * the current load rather than smoothing over the whole run. Latency is the wall time the generator
 * spent executing each operation against the target sink (e.g. the GridGain write), which is the
 * "GridGain execution time" a load test cares about. Note it is a *mean* over the interval: no
 * percentiles are carried on this feed.
 *
 * ### Identity: two ids, deliberately
 * [runId] identifies **this process**; every instance of a distributed run generates its own. That
 * is what lets a consumer count live instances and evict one that died. [runGroup] is the id shared
 * by every instance the toolkit launched together (the plugin's run id), and is what a consumer
 * aggregates and addresses commands by. Without the group id, snapshots from a fleet could not be
 * attributed to the run that produced them.
 */
data class MetricsSnapshot(
    val updatedAtMs: Long,
    val observedTps: Double,
    val avgLatencyMs: Double,
    val totalOps: Long,
    val errorCount: Long,
    /** The rate the limiter is pacing to *right now* — tracks ramps, steps, and live overrides. */
    val targetTps: Double,
    /** Shared across every instance of one launch; the key a consumer aggregates by. */
    val runGroup: String,
    /** Unique to this process; the key a consumer counts and expires instances by. */
    val runId: String,
    /** False in the final snapshot written at run end, so consumers zero out between runs. */
    val active: Boolean,
)
