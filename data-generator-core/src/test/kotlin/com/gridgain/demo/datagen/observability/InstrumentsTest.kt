package com.gridgain.demo.datagen.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class InstrumentsTest {

    private fun instrumentsWithReader(): Pair<Instruments, InMemoryMetricReader> {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val otel = OpenTelemetrySdk.builder().setMeterProvider(provider).build()
        return Instruments(otel) to reader
    }

    @Test fun `noop instruments do not crash and record nothing`() {
        val instruments = Instruments(OpenTelemetry.noop())
        instruments.opLatency.record(1_000.0, Attributes.empty())
        instruments.opCount.add(1, Attributes.empty())
        instruments.opErrors.add(1, Attributes.empty())
        instruments.inFlight.add(1, Attributes.empty())
        instruments.targetRateRef.set(42.0)
        instruments.observedRateRef.set(7.5)
    }

    @Test fun `histogram name and counter names match spec`() {
        val (instruments, reader) = instrumentsWithReader()
        instruments.opLatency.record(2_500_000.0, Attributes.empty())
        instruments.opCount.add(3, Attributes.empty())
        instruments.opErrors.add(1, Attributes.empty())

        val metrics = reader.collectAllMetrics()
        val names = metrics.map { it.name }
        assertThat(names).contains(
            "data_generator.op.latency",
            "data_generator.op.count",
            "data_generator.op.errors",
        )
    }

    @Test fun `gauges read from AtomicReference slots`() {
        val (instruments, reader) = instrumentsWithReader()
        instruments.targetRateRef.set(123.45); instruments.observedRateRef.set(98.7)
        val names = reader.collectAllMetrics().map { it.name }
        assertThat(names).contains("data_generator.target_rate", "data_generator.observed_rate")
    }
}
