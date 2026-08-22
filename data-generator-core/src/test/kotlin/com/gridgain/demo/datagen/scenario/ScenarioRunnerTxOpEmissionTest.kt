package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.observability.Instruments
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.TransactionOutcome
import com.gridgain.demo.datagen.target.WriteOutcome
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * Verifies F12 closure: `op=tx_commit` and `op=tx_rollback` are emitted as separate
 * histogram + counter points alongside the existing `op=put` when the target reports
 * a `TransactionOutcome`. Exercises the runner against handcrafted targets that return
 * fixed outcomes — flavor-specific GG8/GG9 transaction wrapping is covered by the
 * existing target tests.
 */
class ScenarioRunnerTxOpEmissionTest {

    private fun freshInstruments(): Pair<Instruments, InMemoryMetricReader> {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val otel = OpenTelemetrySdk.builder().setMeterProvider(provider).build()
        return Instruments(otel) to reader
    }

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(
            ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
        ))
    ))

    private fun runner(
        dir: Path, target: Target, instruments: Instruments,
        targetName: String = "tx-target",
        count: Long = 5,
    ): ScenarioRunner {
        val data = simpleData()
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        val scenario = ScenarioSpec(
            name = "tx-test",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1000.0),
            duration = CountDurationSpec(count),
            transactionScope = TransactionScope.BUSINESS_EVENT,
            readRatio = 0.0,
        )
        return ScenarioRunner(
            scenario = scenario, data = data, generator = gen, target = target,
            instruments = instruments, targetName = targetName,
        )
    }

    private fun pointsByOp(reader: InMemoryMetricReader, metricName: String, opTag: String): Long =
        reader.collectAllMetrics()
            .first { it.name == metricName }
            .longSumData.points
            .filter { it.attributes.asMap().any { (k, v) -> k.key == "op" && v == opTag } }
            .sumOf { it.value }

    @Test fun `committed transactions emit op_count under op=tx_commit alongside op=put`(
        @TempDir dir: Path,
    ) {
        val (instruments, reader) = freshInstruments()
        runner(dir, AlwaysCommittingTarget(), instruments, count = 5).run()

        assertThat(pointsByOp(reader, "data_generator.op.count", "put")).isEqualTo(5L)
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_commit")).isEqualTo(5L)
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_rollback")).isZero()
    }

    @Test fun `rolled-back transactions emit op_count under op=tx_rollback alongside op=put`(
        @TempDir dir: Path,
    ) {
        val (instruments, reader) = freshInstruments()
        runner(dir, AlwaysRollingBackTarget(IllegalStateException("forced rollback")),
               instruments, count = 4).run()

        assertThat(pointsByOp(reader, "data_generator.op.count", "put")).isEqualTo(4L)
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_rollback")).isEqualTo(4L)
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_commit")).isZero()
    }

    @Test fun `non-transactional targets do not emit any tx-tagged points`(
        @TempDir dir: Path,
    ) {
        val (instruments, reader) = freshInstruments()
        runner(dir, NoTxTarget(), instruments, count = 3).run()

        assertThat(pointsByOp(reader, "data_generator.op.count", "put")).isEqualTo(3L)
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_commit")).isZero()
        assertThat(pointsByOp(reader, "data_generator.op.count", "tx_rollback")).isZero()
    }

    @Test fun `tx_commit latency histogram has at least one observation per commit`(
        @TempDir dir: Path,
    ) {
        val (instruments, reader) = freshInstruments()
        runner(dir, AlwaysCommittingTarget(), instruments, count = 3).run()

        val histogram = reader.collectAllMetrics()
            .first { it.name == "data_generator.op.latency" }
            .histogramData.points
            .filter { it.attributes.asMap().any { (k, v) -> k.key == "op" && v == "tx_commit" } }
        assertThat(histogram).isNotEmpty
        assertThat(histogram.sumOf { it.count }).isEqualTo(3L)
    }
}

private class AlwaysCommittingTarget : Target {
    override val supportsReads = false
    override val supportsTransactions = true
    override fun write(event: BusinessEvent) =
        WriteOutcome(success = true, transactionOutcome = TransactionOutcome.COMMITTED)
    override fun read(cacheName: String, key: Any) = ReadOutcome(success = false)
}

private class AlwaysRollingBackTarget(private val cause: Exception) : Target {
    override val supportsReads = false
    override val supportsTransactions = true
    override fun write(event: BusinessEvent) =
        WriteOutcome(success = false, error = cause, transactionOutcome = TransactionOutcome.ROLLED_BACK)
    override fun read(cacheName: String, key: Any) = ReadOutcome(success = false)
}

private class NoTxTarget : Target {
    override val supportsReads = false
    override val supportsTransactions = false
    override fun write(event: BusinessEvent) =
        WriteOutcome(success = true, transactionOutcome = TransactionOutcome.NONE)
    override fun read(cacheName: String, key: Any) = ReadOutcome(success = false)
}
