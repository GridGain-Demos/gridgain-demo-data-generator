package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.MetricsSpec
import com.gridgain.demo.datagen.metrics.LatencyHistogramBounds
import com.gridgain.demo.datagen.metrics.LiveMetricsReporter
import com.gridgain.demo.datagen.metrics.MetricsRecorder
import com.gridgain.demo.datagen.metrics.MetricsSink
import com.gridgain.demo.datagen.metrics.MetricsSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import kotlin.test.Test

/**
 * Regression guard for the window Task 4's `LiveMetricsReporter` guard opened: `ScenarioRunnerCli`
 * used to build `MetricsRecorder.detached()` unconditionally and then attach a real
 * `LiveMetricsReporter` to it whenever ops.yaml declared a `metrics:` block, which throws
 * `IllegalArgumentException` at startup (see `LiveMetricsReporter.init`). No test exercised that
 * path, so `./gradlew build` stayed green while every metrics-configured run was broken.
 *
 * This does not stand up Kafka or drive the full CLI `resolve()`/`run()` path (which would require
 * a live broker to construct `KafkaMetricsSink` end-to-end without a multi-second `close()` timeout
 * per test). It instead exercises the exact expression `ScenarioRunnerCli.run` uses to size the
 * recorder from a parsed `MetricsSpec`, which is precisely what was missing before Task 6.
 */
class ScenarioRunnerCliMetricsRecorderTest {

    private class NoOpSink : MetricsSink {
        override fun emit(snapshot: MetricsSnapshot) {}
    }

    @Test
    fun `a recorder built from a metrics-configured spec is not detached, and a reporter accepts it`() {
        val spec = MetricsSpec(
            kafkaBootstrap = "kafka:9092",
            topic = "datagen-metrics",
            histogramHighestMs = 60_000L,
            histogramSignificantDigits = 3,
        )

        // This is the same expression ScenarioRunnerCli.run uses to build metricsRecorder from
        // resolution.parsedConfig.ops.metrics.
        val recorder = MetricsRecorder(
            LatencyHistogramBounds(
                highestMs = spec.histogramHighestMs,
                significantDigits = spec.histogramSignificantDigits,
            )
        )

        assertThat(recorder.isDetached).isFalse()

        // Before Task 6, ScenarioRunnerCli built MetricsRecorder.detached() regardless of the
        // metrics block, and wiring a LiveMetricsReporter to that recorder threw here.
        assertThatCode {
            LiveMetricsReporter(
                recorder = recorder,
                sink = NoOpSink(),
                targetTps = { 0.0 },
                runGroup = "g",
                runId = "r",
            )
        }.doesNotThrowAnyException()
    }
}
