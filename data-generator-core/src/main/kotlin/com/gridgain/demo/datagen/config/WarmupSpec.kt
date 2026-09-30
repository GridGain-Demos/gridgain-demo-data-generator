package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Work performed but **not measured** (ops v10+) — the equivalent of Yardstick's `-w/--warmup`.
 *
 * Without it every figure the generator has ever reported included its own cold start: the JVM
 * interpreting before it compiled, the client opening its connections and discovering the topology,
 * the cluster's pages not yet resident. Those operations are real and worth performing — a benchmark
 * that skips them measures a cluster in a state no application ever sees — but counting them makes
 * a p99 a statement about JIT warm-up rather than about the cluster. The shorter the run, the more
 * the cold start dominates, which is exactly backwards from what a quick sanity run should report.
 *
 * Warmup operations still execute, still populate the key space and still hit the target. What they
 * do not do is enter the measurement: the latency histogram is reset when the window closes and the
 * achieved rate is computed from the measured window alone.
 *
 * Discriminated like [DurationSpec], and with the same two bounded kinds. There is deliberately no
 * `until_stop_condition` warmup: a warmup that runs until an operator intervenes is just a run.
 * [MigrateOpsV9toV10] writes `kind: none` into every existing scenario, so an upgraded file reports
 * exactly what it reported before.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = NoWarmupSpec::class, name = "none"),
    JsonSubTypes.Type(value = TimeWarmupSpec::class, name = "time"),
    JsonSubTypes.Type(value = CountWarmupSpec::class, name = "count"),
)
sealed class WarmupSpec

/** Measure from the first operation. Every figure includes the cold start. */
class NoWarmupSpec : WarmupSpec() {
    override fun equals(other: Any?) = other is NoWarmupSpec
    override fun hashCode() = 0
}

/** Discard the first [value] of wall time. ISO-8601, as everywhere else in this file. */
data class TimeWarmupSpec(val value: String) : WarmupSpec()

/**
 * Discard the first [value] operations.
 *
 * Counted across the whole process, not per worker — the warm-up being waited on is the JVM's and
 * the client's, and both are shared. As with `duration: {kind: count}` the boundary can overshoot by
 * up to `concurrency - 1` operations, because workers check it independently.
 */
data class CountWarmupSpec(val value: Long) : WarmupSpec()
