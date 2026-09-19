package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * With `concurrency > 1` every worker thread reports its outcome to the one
 * [StopConditionEvaluator] while another may be evaluating [StopConditionEvaluator.shouldStop],
 * so both its counters and its histogram are shared mutable state on the hot path.
 *
 * Note on what these tests can and cannot prove: the evaluator exposes no count, so a *lost*
 * increment is not directly observable through its public API — an error rate stays near its true
 * value whether or not increments are dropped. These are end-to-end guards that the shared
 * structure survives concurrent use and still reaches its verdict. The counters are `AtomicLong`
 * because a non-volatile `Long` written by many threads is a data race by construction, not
 * because a test caught it.
 */
class StopConditionEvaluatorConcurrencyTest {

    private val threads = 8
    private val opsPerThread = 20_000

    private fun hammer(evaluator: StopConditionEvaluator, success: (Int) -> Boolean) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        (0 until threads).forEach { t ->
            Thread({
                try {
                    start.await()
                    (0 until opsPerThread).forEach { i ->
                        evaluator.recordOutcome(
                            success = success(t),
                            latencyNanos = (i % 1_000 + 1).toLong() * 1_000L,
                        )
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }, "reporter-$t").apply { isDaemon = true }.start()
        }

        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS))
            .describedAs("recording deadlocked or spun")
            .isTrue()
        assertThat(failure.get()).isNull()
    }

    @Test
    fun `an all-failing run still trips the error rate threshold under concurrent reporting`() {
        val evaluator = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.5)), StopSignal())

        hammer(evaluator) { false }

        assertThat(evaluator.shouldStop())
            .describedAs("every operation failed; the condition must fire")
            .contains("error_rate")
    }

    @Test
    fun `an all-succeeding run trips nothing under concurrent reporting`() {
        val evaluator = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.5)), StopSignal())

        hammer(evaluator) { true }

        assertThat(evaluator.shouldStop())
            .describedAs("nothing failed; a race must not manufacture a stop")
            .isNull()
    }

    @Test
    fun `evaluating while workers record does not throw`() {
        val evaluator = StopConditionEvaluator(
            listOf(LatencyP99StopSpec("PT10S")), StopSignal(),
        )
        val evaluatorFailure = AtomicReference<Throwable?>(null)
        val stop = AtomicReference(false)

        // The run loop polls shouldStop() after every tick, so evaluation genuinely overlaps
        // recording; quantile computation reads the same histogram the workers are writing.
        val poller = Thread({
            try {
                while (!stop.get()) evaluator.shouldStop()
            } catch (e: Throwable) {
                evaluatorFailure.compareAndSet(null, e)
            }
        }, "poller").apply { isDaemon = true }
        poller.start()

        hammer(evaluator) { it % 2 == 0 }
        stop.set(true)
        poller.join(10_000)

        assertThat(evaluatorFailure.get())
            .describedAs("a quantile read concurrent with recording must not throw")
            .isNull()
    }
}
