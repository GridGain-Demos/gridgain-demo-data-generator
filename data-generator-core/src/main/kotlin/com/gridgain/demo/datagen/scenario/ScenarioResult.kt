package com.gridgain.demo.datagen.scenario

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.HdrHistogram.Histogram
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The run's latency, in **milliseconds**.
 *
 * Milliseconds rather than the microseconds the histogram tracks, because every consumer of these
 * numbers already speaks milliseconds: the toolkit's `DataGeneratorRunStats`, the Grafana
 * dashboards, and the `metrics.histogram_highest_ms` the operator configured.
 *
 * A mean is carried alongside the percentiles deliberately. It is the figure the counters could
 * already produce, so reporting both makes the gap between them visible — and a mean far below the
 * p99 is the signature of a tail problem that a throughput number alone hides completely.
 */
data class LatencySummary(
    val p50Ms: Double,
    val p90Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val meanMs: Double,
) {
    companion object {
        /** A run that recorded nothing. Zeros rather than an absent value: a run still reports. */
        val EMPTY = LatencySummary(0.0, 0.0, 0.0, 0.0, 0.0)

        /** Summarise a whole-run histogram. [histogram] records **microseconds**. */
        fun from(histogram: Histogram): LatencySummary {
            if (histogram.totalCount == 0L) return EMPTY
            fun ms(micros: Number) = micros.toDouble() / 1_000.0
            return LatencySummary(
                p50Ms = ms(histogram.getValueAtPercentile(50.0)),
                p90Ms = ms(histogram.getValueAtPercentile(90.0)),
                p99Ms = ms(histogram.getValueAtPercentile(99.0)),
                maxMs = ms(histogram.maxValue),
                meanMs = ms(histogram.mean),
            )
        }
    }
}

data class ScenarioResult(
    val scenarioName: String,
    val achievedRate: Double,
    val errorCount: Long,
    val successCount: Long,
    val stopReason: String,
    val wallTime: Duration,
    val latency: LatencySummary,
    /**
     * Reads that succeeded and found nothing.
     *
     * Kept apart from [errorCount] because a miss is not a failure — it says the key space is wider
     * than what has been written into it. A read-heavy run with a high miss count is measuring the
     * cluster's empty path, which is fast and means nothing.
     */
    val readMissCount: Long = 0L,
    /** Row-level writes performed — a business event with children is more than one. */
    val rowsWritten: Long = 0L,
    /**
     * Rows written per write operation: the run's measured fan-out.
     *
     * One operation is one business event, which a `data.yaml` with `parent-fk-ref` children turns
     * into several row writes across several caches. [achievedRate] counts **operations**, so
     * without this a figure looks like puts/sec while counting events, and the same run against a
     * single-schema file would report a far higher number for identical cluster work.
     *
     * `1.0` means one operation was exactly one put — the benchmark-shaped case.
     */
    val rowsPerWrite: Double = 0.0,
    /**
     * Operations inside the measured window — the run's total minus whatever the warmup consumed.
     *
     * [achievedRate] and [latency] both describe these operations and no others. With
     * `warmup: {kind: none}` this equals `successCount + errorCount`.
     */
    val measuredOperations: Long = 0L,
    /** Wall time of the measured window. [wallTime] remains the whole run, warmup included. */
    val measuredWindow: Duration = Duration.ZERO,
) {
    companion object {
        private val mapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

        fun write(result: ScenarioResult, path: Path) {
            Files.createDirectories(path.parent)
            val map = linkedMapOf(
                "scenario_name" to result.scenarioName,
                "achieved_rate" to result.achievedRate,
                "error_count" to result.errorCount,
                "success_count" to result.successCount,
                "read_miss_count" to result.readMissCount,
                "rows_written" to result.rowsWritten,
                "rows_per_write" to result.rowsPerWrite,
                "stop_reason" to result.stopReason,
                "wall_time" to result.wallTime.toString(),
                // The window achieved_rate and the percentiles below actually describe. Emitted
                // even when there was no warmup, so a reader never has to guess whether a figure
                // covers the whole run.
                "measured_operations" to result.measuredOperations,
                "measured_window" to result.measuredWindow.toString(),
                // Flat keys rather than a nested `latency:` map, matching the six that were already
                // here — this file is read by shell and by the toolkit's host runner, and a flat
                // key is one grep away in both.
                "latency_p50_ms" to result.latency.p50Ms,
                "latency_p90_ms" to result.latency.p90Ms,
                "latency_p99_ms" to result.latency.p99Ms,
                "latency_max_ms" to result.latency.maxMs,
                "latency_mean_ms" to result.latency.meanMs,
            )
            mapper.writeValue(path.toFile(), map)
        }
    }
}
