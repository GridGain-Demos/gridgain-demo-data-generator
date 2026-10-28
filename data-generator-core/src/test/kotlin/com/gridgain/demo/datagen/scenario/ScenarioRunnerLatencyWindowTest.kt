package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.NoWarmupSpec
import com.gridgain.demo.datagen.config.OperationMix
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UnboundedKeySpaceSpec
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.test.Test

/**
 * What the latency window contains.
 *
 * The window used to open before key selection and row generation, so every sample carried this
 * process's own cost while the code claimed to be timing the GridGain round trip. Reviewed and
 * corrected 2026-10-29; these tests are what stop it drifting back.
 */
class ScenarioRunnerLatencyWindowTest {

    private fun data() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(
            ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
        ))
    ))

    private fun scenario(name: String, ops: OperationMix, count: Long = 8) = ScenarioSpec(
        concurrency = 1, name = name, rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100_000.0), duration = CountDurationSpec(count),
        transactionScope = TransactionScope.NONE, operations = ops,
        warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
    )

    private fun runner(dir: Path, scenario: ScenarioSpec, target: Target): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data(), "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario, data(), listOf(gen), target, decisionRandom = Random(1L))
    }

    /** Sleeps a known time inside the target call and nowhere else. */
    private class SlowTarget(private val millis: Long) : Target {
        override val supportsReads = true
        override val supportsTransactions = false
        var writes = 0
        override fun write(event: BusinessEvent): WriteOutcome {
            Thread.sleep(millis); writes++
            return WriteOutcome(success = true)
        }
        override fun read(cacheName: String, key: Any): ReadOutcome {
            Thread.sleep(millis)
            return ReadOutcome(success = true, value = null)
        }
    }

    /**
     * The measured latency is the target's time, not the target's time plus the client's.
     *
     * The target sleeps 40ms and is the only thing that blocks, so every sample must sit just above
     * 40ms. Generation and key selection happening inside the window would push the mean above the
     * sleep by this process's own cost; the ceiling here is loose enough not to be flaky and tight
     * enough that re-admitting a row build would have to be nearly free to pass.
     */
    @Test
    fun `measured latency tracks the target call, not the client's own work`(@TempDir dir: Path) {
        val target = SlowTarget(millis = 40)
        val result = runner(dir, scenario("writes", OperationMix(put = 1.0, get = 0.0, putGet = 0.0)), target).run()

        assertThat(target.writes).isEqualTo(8)
        assertThat(result.latency.meanMs)
            .describedAs("the target sleeps 40ms; the window must not open before that")
            .isGreaterThanOrEqualTo(39.0)
            .isLessThan(48.0)
    }

    // NOT TESTED HERE: that a generation failure is caught rather than killing the worker.
    // `BusinessEventGenerator` is a final class with no seam to make it throw, and every value
    // source that could fail does so at construction rather than per event. The guard is in
    // `ScenarioRunner.tick` and was added deliberately when the preparation moved out of the
    // measured block's catch; it is verified by reading, not by this file. A seam on the generator
    // would make it testable and is worth adding if that code is touched again.
}
