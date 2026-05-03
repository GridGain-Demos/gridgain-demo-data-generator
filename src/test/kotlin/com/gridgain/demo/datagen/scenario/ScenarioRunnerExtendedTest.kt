package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.RateStep
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

class ScenarioRunnerExtendedTest {

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, valueSource = SequenceSpec(1, 1))))
    ))

    private fun runner(dir: Path, scenario: ScenarioSpec, target: Target = InMemoryTarget()): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(simpleData(), "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario = scenario, generator = gen, target = target,
            untilStopCap = Duration.ofMillis(500))
    }

    @Test
    fun `ramped rate runs to completion`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "ramped-test",
            rootSchemas = listOf("customer"),
            rate = RampedRateSpec(from = 100.0, to = 1000.0, over = "PT0.1S"),
            duration = TimeDurationSpec("PT0.2S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.successCount).isGreaterThan(20L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `stepped rate runs to completion`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "stepped-test",
            rootSchemas = listOf("customer"),
            rate = SteppedRateSpec(listOf(
                RateStep(rate = 200.0, hold = "PT0.05S"),
                RateStep(rate = 500.0, hold = "PT0.05S"),
            )),
            duration = TimeDurationSpec("PT0.1S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.successCount).isGreaterThan(15L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `until_stop_condition runs until the cap`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "until-stop-no-stop",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 100.0),
            duration = UntilStopDurationSpec(),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.stopReason).isEqualTo("until_stop_condition cap reached")
        assertThat(result.wallTime.toMillis()).isBetween(450L, 800L)
    }

    @Test
    fun `until_stop_condition stops on error_rate trigger`(@TempDir dir: Path) {
        val target = object : Target {
            override val supportsReads: Boolean = false
            override val supportsTransactions: Boolean = false
            private var n = 0
            override fun write(event: BusinessEvent): WriteOutcome {
                n++
                return if (n <= 100) WriteOutcome(success = true)
                else WriteOutcome(success = false, error = RuntimeException("boom"))
            }
        }
        val scenario = ScenarioSpec(
            name = "until-stop-on-error",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 1000.0),
            duration = UntilStopDurationSpec(),
            stopConditions = listOf(ErrorRateStopSpec(threshold = 0.05)),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(result.stopReason).contains("error_rate")
        assertThat(result.errorCount).isGreaterThan(0L)
    }
}
