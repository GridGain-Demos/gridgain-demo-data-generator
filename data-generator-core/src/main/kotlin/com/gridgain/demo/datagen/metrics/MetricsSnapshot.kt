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
 * "GridGain execution time" a load test cares about. Note [avgLatencyMs] is a *mean* over the
 * interval. The feed carries no scalar percentiles; what it carries instead is
 * [runLatencyHistogram], the whole-run histogram a consumer reads any percentile off — and merges
 * across instances, which a published percentile could not be.
 *
 * ### Interval versus lifetime
 * [observedTps]/[avgLatencyMs] are interval rates, which is what makes a live graph track the
 * current load. [runAvgTps]/[runAvgLatencyMs]/[runLatencyHistogram] are whole-run, which is what
 * makes an end-of-run summary defensible. [totalOps] and [errorCount] were already lifetime
 * cumulative and are unchanged.
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
    /** Lifetime: [totalOps] over elapsed run seconds, for this instance. */
    val runAvgTps: Double,
    /** Lifetime: cumulative execution time over [totalOps], in ms, for this instance. */
    val runAvgLatencyMs: Double,
    /**
     * This instance's whole-run latency histogram — HdrHistogram compressed encoding, base64,
     * microseconds. See [HistogramCodec].
     *
     * On the wire rather than scalar percentiles because **percentiles do not compose**: a consumer
     * aggregating a fleet must merge the histograms, and the max p90 across instances is the worst
     * instance's p90, not the fleet's. Empty string only if this instance recorded nothing.
     */
    val runLatencyHistogram: String,
    /** The rate the limiter is pacing to *right now* — tracks ramps, steps, and live overrides. */
    val targetTps: Double,
    /** Shared across every instance of one launch; the key a consumer aggregates by. */
    val runGroup: String,
    /** Unique to this process; the key a consumer counts and expires instances by. */
    val runId: String,
    /** False in the final snapshot written at run end, so consumers zero out between runs. */
    val active: Boolean,
)
