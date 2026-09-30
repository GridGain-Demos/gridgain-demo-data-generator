package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.DurationSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.config.NoWarmupSpec
import com.gridgain.demo.datagen.config.OperationMix
import com.gridgain.demo.datagen.config.UnboundedKeySpaceSpec
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.generation.workerStripe
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * The runner driving several worker threads from one scenario. The workers share the target, the
 * rate limiter, the key registry and the stop machinery, and own a generator each.
 */
class ScenarioRunnerConcurrencyTest {

    /** Records every key written, so duplicates across worker threads are visible. */
    private class RecordingTarget : Target {
        val keys = ConcurrentLinkedQueue<Any>()
        val concurrentWriters = AtomicInteger(0)
        @Volatile var maxConcurrentWriters: Int = 0
        override val supportsReads: Boolean = false
        override val supportsTransactions: Boolean = false
        override fun write(event: BusinessEvent): WriteOutcome {
            val inFlight = concurrentWriters.incrementAndGet()
            if (inFlight > maxConcurrentWriters) maxConcurrentWriters = inFlight
            try {
                keys.add(event.parentRow["id"]!!)
                Thread.sleep(0, 50_000)   // stand in for a round trip, so overlap is real
                return WriteOutcome(success = true)
            } finally {
                concurrentWriters.decrementAndGet()
            }
        }
        override fun read(cacheName: String, key: Any): ReadOutcome = ReadOutcome(success = false)
    }

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))))
    ))

    private fun scenario(
        duration: DurationSpec,
        concurrency: Int,
        stopConditions: List<com.gridgain.demo.datagen.config.StopConditionSpec> = emptyList(),
    ) =
        ScenarioSpec(
            concurrency = concurrency,
            name = "concurrent",
            rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(opsPerSecond = 1_000_000.0),   // never the constraint
            duration = duration,
            stopConditions = stopConditions,
            transactionScope = TransactionScope.NONE,
            operations = OperationMix(put = 1.0, get = 0.0, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )

    /** One generator per worker, each striped so no two emit the same key. */
    private fun generatorsFor(dir: Path, data: DataConfig, concurrency: Int): List<BusinessEventGenerator> =
        (0 until concurrency).map { worker ->
            val factory = ValueSourceFactory(
                yamlDataRoot = dir,
                seed = 1L,
                partitionStripe = workerStripe(null, workerIndex = worker, concurrency = concurrency),
            )
            BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        }

    @Test
    fun `four workers write no duplicate keys`(@TempDir dir: Path) {
        val data = simpleData()
        val target = RecordingTarget()
        val runner = ScenarioRunner(
            scenario = scenario(CountDurationSpec(2_000L), concurrency = 4),
            data = data,
            generators = generatorsFor(dir, data, concurrency = 4),
            target = target,
        )

        val result = runner.run()

        assertThat(target.keys)
            .describedAs("striped sequences must never collide, or GridGain sees overwrites")
            .doesNotHaveDuplicates()
        assertThat(result.successCount + result.errorCount)
            .describedAs("a count run with N workers may overshoot by at most N-1 in-flight ops")
            .isBetween(2_000L, 2_003L)
    }

    @Test
    fun `workers genuinely run in parallel`(@TempDir dir: Path) {
        val data = simpleData()
        val target = RecordingTarget()
        val runner = ScenarioRunner(
            scenario = scenario(CountDurationSpec(400L), concurrency = 4),
            data = data,
            generators = generatorsFor(dir, data, concurrency = 4),
            target = target,
        )

        runner.run()

        assertThat(target.maxConcurrentWriters)
            .describedAs("if this is 1 the workers were serialised and concurrency bought nothing")
            .isGreaterThan(1)
    }

    @Test
    fun `a stop signal ends every worker`(@TempDir dir: Path) {
        val data = simpleData()
        val signal = StopSignal()
        val target = RecordingTarget()
        val runner = ScenarioRunner(
            scenario = scenario(UntilStopDurationSpec(), concurrency = 4, stopConditions = listOf(ExternalSignalStopSpec())),
            data = data,
            generators = generatorsFor(dir, data, concurrency = 4),
            target = target,
            stopSignal = signal,
        )

        // Raised from outside, exactly as the shutdown hook and the control channel do.
        Thread({ Thread.sleep(300); signal.raise("SIGTERM") }, "signaller").apply { isDaemon = true }.start()
        val result = runner.run()

        assertThat(result.stopReason).contains("SIGTERM")
        assertThat(target.concurrentWriters.get())
            .describedAs("run() must not return while a worker is still writing")
            .isZero()
    }

    @Test
    fun `a single generator still runs on the calling thread`(@TempDir dir: Path) {
        val data = simpleData()
        val callingThread = Thread.currentThread().name
        val observed = ConcurrentLinkedQueue<String>()
        val target = object : Target {
            override val supportsReads: Boolean = false
            override val supportsTransactions: Boolean = false
            override fun write(event: BusinessEvent): WriteOutcome {
                observed.add(Thread.currentThread().name)
                return WriteOutcome(success = true)
            }
            override fun read(cacheName: String, key: Any): ReadOutcome = ReadOutcome(success = false)
        }
        val runner = ScenarioRunner(
            scenario = scenario(CountDurationSpec(10L), concurrency = 1),
            data = data,
            generators = generatorsFor(dir, data, concurrency = 1),
            target = target,
        )

        runner.run()

        assertThat(observed.toSet())
            .describedAs("concurrency 1 must spawn no threads, so today's runs are unchanged")
            .containsExactly(callingThread)
    }
}
