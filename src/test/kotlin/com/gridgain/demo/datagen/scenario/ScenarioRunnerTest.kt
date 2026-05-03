package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerTest {

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, SequenceSpec(1, 1))))
    ))

    private fun runner(dir: Path, scenario: ScenarioSpec, target: InMemoryTarget = InMemoryTarget()): ScenarioRunner {
        val data = simpleData()
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario = scenario, generator = gen, target = target)
    }

    @Test
    fun `count duration writes exactly N events`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "count-50",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 1000.0),
            duration = CountDurationSpec(value = 50),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(target.writes).hasSize(50)
        assertThat(result.successCount).isEqualTo(50)
        assertThat(result.errorCount).isEqualTo(0)
        assertThat(result.stopReason).isEqualTo("count reached")
        assertThat(result.scenarioName).isEqualTo("count-50")
    }

    @Test
    fun `time duration runs for at least the configured duration`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "time-200ms",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 100.0),
            duration = TimeDurationSpec("PT0.2S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(result.wallTime.toMillis()).isBetween(180L, 600L)
        assertThat(result.successCount).isBetween(15L, 35L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `unsupported rate kind is rejected`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "ramped",
            rootSchemas = listOf("customer"),
            rate = RampedRateSpec(from = 1.0, to = 100.0, over = "PT1S"),
            duration = CountDurationSpec(value = 5),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        assertThatThrownBy { runner(dir, scenario).run() }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("ramped")
            .hasMessageContaining("Plan 5")
    }
}
