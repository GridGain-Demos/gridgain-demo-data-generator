package com.gridgain.demo.datagen.observability

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * `data_generator.op.latency` must land in buckets a `histogram_quantile` can use.
 *
 * It records **nanoseconds**, and OpenTelemetry's default explicit bucket boundaries stop at
 * 10,000 — chosen for a metric measured in milliseconds. Against a nanosecond value that makes the
 * largest finite bucket 10 microseconds, so every operation the generator has ever performed landed
 * in `+Inf` and every percentile query over the Prometheus series returned nothing usable. The
 * histogram was emitted, scraped, stored and unqueryable.
 *
 * The unit stays nanoseconds rather than being switched to milliseconds, deliberately: the
 * Prometheus series is named for its unit (`data_generator_op_latency_nanoseconds_bucket`), and
 * renaming it would silently empty every existing dashboard panel and recording rule. Advising the
 * boundaries fixes the defect without moving the series.
 */
class LatencyHistogramBucketsTest {

    private fun record(vararg latencyNanos: Double): HistogramPointData {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val instruments = Instruments(OpenTelemetrySdk.builder().setMeterProvider(provider).build())

        latencyNanos.forEach { instruments.opLatency.record(it, Attributes.empty()) }

        val metric = reader.collectAllMetrics().single { it.name == Instruments.OP_LATENCY }
        return metric.histogramData.points.single()
    }

    @Test
    fun `the bucket boundaries span the range a cache operation actually falls in`() {
        val point = record(1.0)
        val boundaries = point.boundaries

        assertThat(boundaries)
            .describedAs("the default boundaries top out at 10,000ns — 10 microseconds")
            .isNotEqualTo(
                listOf(0.0, 5.0, 10.0, 25.0, 50.0, 75.0, 100.0, 250.0, 500.0, 750.0,
                       1000.0, 2500.0, 5000.0, 7500.0, 10000.0)
            )
        assertThat(boundaries.last())
            .describedAs("a boundary must sit above a slow operation, or every real op is +Inf")
            .isGreaterThanOrEqualTo(1_000_000_000.0)       // 1 second
        assertThat(boundaries.first())
            .describedAs("the fast end has to resolve a sub-millisecond cache hit")
            .isLessThanOrEqualTo(100_000.0)                // 0.1 ms
    }

    @Test
    fun `a typical spread of operations does not all fall into the overflow bucket`() {
        // 50 us, 300 us, 2 ms, 40 ms — an ordinary cache latency spread.
        val point = record(50_000.0, 300_000.0, 2_000_000.0, 40_000_000.0)

        val overflow = point.counts.last()
        assertThat(overflow)
            .describedAs(
                "every operation landed in +Inf, so histogram_quantile has nothing to interpolate " +
                    "between and every percentile panel reads the same meaningless value"
            )
            .isLessThan(point.count)
        assertThat(point.counts.dropLast(1).sum())
            .describedAs("most of an ordinary spread must land in finite buckets")
            .isGreaterThanOrEqualTo(3L)
    }
}
