package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.DurationSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.metrics.HistogramCodec
import com.gridgain.demo.datagen.metrics.LatencyHistogramBounds
import com.gridgain.demo.datagen.metrics.LiveMetricsReporter
import com.gridgain.demo.datagen.metrics.MetricsRecorder
import com.gridgain.demo.datagen.metrics.MetricsSink
import com.gridgain.demo.datagen.metrics.MetricsSnapshot
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Graceful stop, driven by raising the [StopSignal] directly rather than by sending a real signal.
 * The shutdown hook that raises it in production (`ScenarioRunnerCli.run`) does nothing else, so
 * everything worth asserting — the loop exits, the run's metrics are complete, the reason reaches
 * the [ScenarioResult] — is reachable this way and stays reachable in a unit test.
 */
class ScenarioRunnerStopSignalTest {

    private class CapturingSink : MetricsSink {
        val emitted = mutableListOf<MetricsSnapshot>()
        override fun emit(snapshot: MetricsSnapshot) { emitted.add(snapshot) }
    }

    /** Raises [signal] on the [afterWrites]th write, standing in for a SIGTERM or a `stop`
     *  command arriving mid-tick. */
    private class SignallingTarget(
        private val afterWrites: Int,
        private val signal: StopSignal,
        private val reason: String,
    ) : Target {
        override val supportsReads: Boolean = false
        override val supportsTransactions: Boolean = false
        var writes: Int = 0
            private set
        override fun write(event: BusinessEvent): WriteOutcome {
            writes++
            if (writes == afterWrites) signal.raise(reason)
            return WriteOutcome(success = true)
        }
        override fun read(cacheName: String, key: Any): ReadOutcome = ReadOutcome(success = false)
    }

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))))
    ))

    private fun scenario(name: String, duration: DurationSpec) = ScenarioSpec(
        name = name,
        rootSchemas = listOf("customer"),
        // Fast enough that the assertions are about the signal, never about pacing.
        rate = ConstantRateSpec(opsPerSecond = 100_000.0),
        duration = duration,
        transactionScope = TransactionScope.NONE,
        readRatio = 0.0,
    )

    private fun runner(
        dir: Path,
        scenario: ScenarioSpec,
        target: Target,
        stopSignal: StopSignal,
        metrics: MetricsRecorder = MetricsRecorder.detached(),
        rateLimiter: ControllableRateLimiter = ControllableRateLimiter(ScenarioRunner.buildRateLimiter(scenario)),
    ): ScenarioRunner {
        val data = simpleData()
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(
            scenario = scenario, data = data, generator = gen, target = target,
            // Long enough that an `until_stop_condition` test that reached it would be a failure,
            // not a slow pass.
            untilStopCap = Duration.ofMinutes(5),
            metrics = metrics,
            rateLimiter = rateLimiter,
            stopSignal = stopSignal,
        )
    }

    @Test
    fun `a signal ends a count run that is nowhere near its count`(@TempDir dir: Path) {
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = 5, signal = signal, reason = "operator stop command")

        val result = runner(dir, scenario("counted", CountDurationSpec(1_000_000L)), target, signal).run()

        assertThat(result.stopReason).isEqualTo("${ScenarioRunner.STOPPED_BY_SIGNAL}operator stop command")
        assertThat(result.successCount)
            .describedAs("the in-flight tick finishes, and nothing after it starts")
            .isEqualTo(5L)
        assertThat(target.writes).isEqualTo(5)
    }

    @Test
    fun `a signal ends a time run long before its duration elapses`(@TempDir dir: Path) {
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = 3, signal = signal, reason = "SIGTERM")

        val result = runner(dir, scenario("timed", TimeDurationSpec("PT1H")), target, signal).run()

        assertThat(result.stopReason).isEqualTo("${ScenarioRunner.STOPPED_BY_SIGNAL}SIGTERM")
        assertThat(result.successCount).isEqualTo(3L)
        assertThat(result.wallTime).isLessThan(Duration.ofMinutes(1))
    }

    @Test
    fun `a signal ends an until_stop_condition run before its cap`(@TempDir dir: Path) {
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = 7, signal = signal, reason = "SIGTERM")

        val result = runner(dir, scenario("unbounded", UntilStopDurationSpec()), target, signal).run()

        assertThat(result.stopReason).isEqualTo("${ScenarioRunner.STOPPED_BY_SIGNAL}SIGTERM")
        assertThat(result.successCount).isEqualTo(7L)
    }

    @Test
    fun `a run that finishes its duration is not reported as stopped by a signal`(@TempDir dir: Path) {
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = Int.MAX_VALUE, signal = signal, reason = "unused")

        val result = runner(dir, scenario("counted", CountDurationSpec(20L)), target, signal).run()

        assertThat(result.stopReason)
            .describedAs("a consumer must be able to tell a completed run from an interrupted one")
            .isEqualTo("count reached")
        assertThat(result.stopReason).doesNotContain(ScenarioRunner.STOPPED_BY_SIGNAL)
    }

    @Test
    fun `the run's metrics are complete when the signal stops it`(@TempDir dir: Path) {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = 40, signal = signal, reason = "SIGTERM")
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { 100_000.0 },
            runGroup = "grp", runId = "run-1",
        )

        val result = runner(dir, scenario("counted", CountDurationSpec(1_000_000L)), target, signal, recorder).run()
        // The end-of-run path `ScenarioRunnerCli.run` performs in its `finally` — this is the step
        // the shutdown hook's bounded wait exists to protect.
        reporter.close()

        val last = sink.emitted.last()
        assertThat(last.active).isFalse()
        assertThat(last.totalOps)
            .describedAs("the final snapshot carries whole-run figures for every op the run did")
            .isEqualTo(result.successCount + result.errorCount)
        assertThat(last.totalOps).isEqualTo(40L)
        assertThat(HistogramCodec.decode(last.runLatencyHistogram).totalCount).isEqualTo(40L)
    }

    @Test
    fun `a generator paused at rate zero can still be stopped`(@TempDir dir: Path) {
        val signal = StopSignal()
        val target = SignallingTarget(afterWrites = Int.MAX_VALUE, signal = signal, reason = "unused")
        val scenario = scenario("paused", CountDurationSpec(1_000_000L))
        val limiter = ControllableRateLimiter(ScenarioRunner.buildRateLimiter(scenario))
        val runner = runner(dir, scenario, target, signal, rateLimiter = limiter)
        // The state a fleet is left in by a `targetTpsPerInstance: 0.0` command: parked inside
        // `acquire()`, not in the run loop, so nothing polls the stop signal.
        limiter.setRate(0.0)

        val finished = CountDownLatch(1)
        var result: ScenarioResult? = null
        Thread({ result = runner.run(); finished.countDown() }, "paused-run").apply {
            isDaemon = true
            start()
        }
        // Long enough to be parked rather than merely slow to start.
        assertThat(finished.await(300, TimeUnit.MILLISECONDS))
            .describedAs("a paused run must not end on its own")
            .isFalse()

        signal.raise("SIGTERM")

        assertThat(finished.await(5, TimeUnit.SECONDS))
            .describedAs("a paused run must still stop: without the limiter release it waits forever")
            .isTrue()
        assertThat(result!!.stopReason).isEqualTo("${ScenarioRunner.STOPPED_BY_SIGNAL}SIGTERM")
    }
}
