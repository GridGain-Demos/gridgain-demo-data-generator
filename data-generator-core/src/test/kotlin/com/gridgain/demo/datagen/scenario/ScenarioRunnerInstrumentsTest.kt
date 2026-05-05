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
import com.gridgain.demo.datagen.target.InMemoryTarget
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerInstrumentsTest {

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
        dir: Path,
        scenario: ScenarioSpec,
        target: Target,
        instruments: Instruments,
        targetName: String,
    ): ScenarioRunner {
        val data = simpleData()
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(
            scenario = scenario, data = data, generator = gen, target = target,
            instruments = instruments, targetName = targetName,
        )
    }

    private fun scenario(count: Int, readRatio: Double, opsPerSecond: Double = 1000.0) = ScenarioSpec(
        name = "instrument-test",
        target = "t",
        rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(opsPerSecond),
        duration = CountDurationSpec(count.toLong()),
        transactionScope = TransactionScope.NONE,
        readRatio = readRatio,
    )

    @Test fun `successful writes record op_count and op_latency under op=put`(@TempDir dir: Path) {
        val (instruments, reader) = freshInstruments()
        runner(
            dir,
            scenario = scenario(count = 5, readRatio = 0.0),
            target = InMemoryTarget(supportsReads = false, supportsTransactions = false),
            instruments = instruments, targetName = "in-memory",
        ).run()

        val metrics = reader.collectAllMetrics().associateBy { it.name }
        val countSum = metrics.getValue("data_generator.op.count").longSumData.points
            .filter { it.attributes.asMap().any { (k, v) -> k.key == "op" && v == "put" } }
            .sumOf { it.value }
        assertThat(countSum).isEqualTo(5L)

        assertThat(metrics).containsKey("data_generator.op.latency")
        assertThat(metrics).doesNotContainKey("does-not-exist")
    }

    @Test fun `failures record op_errors tagged by exception class`(@TempDir dir: Path) {
        val (instruments, reader) = freshInstruments()
        runner(
            dir,
            scenario = scenario(count = 3, readRatio = 0.0),
            target = AlwaysFailingTarget(IllegalStateException("boom")),
            instruments = instruments, targetName = "failing",
        ).run()

        val errors = reader.collectAllMetrics().first { it.name == "data_generator.op.errors" }
            .longSumData.points
        assertThat(errors).isNotEmpty
        assertThat(errors.any { p ->
            p.attributes.asMap().any { (k, v) -> k.key == "exception" && v == "IllegalStateException" }
        }).isTrue()
    }

    @Test fun `target_rate gauge reflects configured constant rate`(@TempDir dir: Path) {
        val (instruments, reader) = freshInstruments()
        runner(
            dir,
            scenario = scenario(count = 1, readRatio = 0.0, opsPerSecond = 17.0),
            target = InMemoryTarget(supportsReads = false, supportsTransactions = false),
            instruments = instruments, targetName = "in-memory",
        ).run()
        val tr = reader.collectAllMetrics().first { it.name == "data_generator.target_rate" }
            .doubleGaugeData.points.first().value
        assertThat(tr).isEqualTo(17.0)
    }
}

private class AlwaysFailingTarget(private val cause: Exception) : Target {
    override val supportsReads: Boolean = false
    override val supportsTransactions: Boolean = false
    override fun write(event: BusinessEvent): WriteOutcome { throw cause }
    override fun read(cacheName: String, key: Any): ReadOutcome { throw cause }
}
