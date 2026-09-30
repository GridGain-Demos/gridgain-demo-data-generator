package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

class ScenarioResultTest {

    private fun result(latency: LatencySummary = LatencySummary.EMPTY) = ScenarioResult(
        scenarioName = "alpha",
        achievedRate = 95.4,
        errorCount = 3,
        successCount = 1000,
        stopReason = "duration elapsed",
        wallTime = Duration.ofSeconds(10),
        latency = latency,
    )

    @Test
    fun `writes a yaml file under run directory`(@TempDir dir: Path) {
        val out = dir.resolve("result.yaml")
        ScenarioResult.write(result(), out)
        val text = Files.readString(out)
        assertThat(text).contains("scenario_name: \"alpha\"")
        assertThat(text).contains("achieved_rate: 95.4")
        assertThat(text).contains("error_count: 3")
        assertThat(text).contains("success_count: 1000")
        assertThat(text).contains("stop_reason: \"duration elapsed\"")
        assertThat(text).contains("wall_time: \"PT10S\"")
    }

    /**
     * The run's own results file has to carry latency, not just throughput.
     *
     * Until now it carried six scalars and none of them was a latency. The whole-run histogram was
     * being recorded on every run and read by nobody: the only way to obtain a percentile was to
     * declare a `metrics:` block, stand up a Kafka broker, consume the live feed and decode a base64
     * HdrHistogram off it. So a throughput figure was quotable and a p99 was not, and the toolkit's
     * own `p90LatencyMs`/`p99LatencyMs` fields could only ever be filled in by the UI — a teardown
     * run from the CLI recorded nulls.
     */
    @Test
    fun `writes latency percentiles alongside throughput`(@TempDir dir: Path) {
        val out = dir.resolve("result.yaml")
        val latency = LatencySummary(
            p50Ms = 1.5, p90Ms = 4.25, p99Ms = 12.0, maxMs = 98.5, meanMs = 2.75,
        )
        ScenarioResult.write(result(latency), out)

        val text = Files.readString(out)
        assertThat(text).contains("latency_p50_ms: 1.5")
        assertThat(text).contains("latency_p90_ms: 4.25")
        assertThat(text).contains("latency_p99_ms: 12.0")
        assertThat(text).contains("latency_max_ms: 98.5")
        assertThat(text).contains("latency_mean_ms: 2.75")
    }

    /**
     * The histogram tracks microseconds; the results file reports milliseconds, because that is the
     * unit every consumer of these numbers already speaks — the plugin's `DataGeneratorRunStats`,
     * the Grafana dashboards, and the `histogram_highest_ms` the operator configured.
     */
    @Test
    fun `summarises a microsecond histogram into milliseconds`() {
        val histogram = org.HdrHistogram.Histogram(60_000_000L, 3)
        repeat(90) { histogram.recordValue(1_000L) }      // 90 ops at 1 ms
        repeat(9) { histogram.recordValue(10_000L) }      //  9 ops at 10 ms
        histogram.recordValue(100_000L)                   //  1 op at 100 ms

        val summary = LatencySummary.from(histogram)

        assertThat(summary.p50Ms).isCloseTo(1.0, within(0.01))
        assertThat(summary.p90Ms).isCloseTo(1.0, within(0.01))
        // The 99th of 100 samples is the last 10 ms one, not the single 100 ms outlier — which is
        // the point of also reporting the max: a p99 cannot see a one-in-a-hundred stall.
        assertThat(summary.p99Ms).isCloseTo(10.0, within(0.05))
        assertThat(summary.maxMs).isCloseTo(100.0, within(0.5))
        assertThat(summary.meanMs).isCloseTo(2.8, within(0.1))
    }

    /** An empty run must produce zeros rather than fail — a run that did nothing still reports. */
    @Test
    fun `an empty histogram summarises to zeros`() {
        val summary = LatencySummary.from(org.HdrHistogram.Histogram(60_000_000L, 3))
        assertThat(summary).isEqualTo(LatencySummary.EMPTY)
    }
}
