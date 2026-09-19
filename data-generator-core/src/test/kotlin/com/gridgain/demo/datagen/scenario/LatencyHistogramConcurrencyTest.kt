package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * [LatencyHistogram] is fed by [StopConditionEvaluator] once per operation, so with
 * `concurrency > 1` every worker thread records into it at the full rate of the run.
 */
class LatencyHistogramConcurrencyTest {

    private val threads = 8
    private val samplesPerThread = 20_000

    @Test
    fun `concurrent records are all retained`() {
        val histogram = LatencyHistogram()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        (0 until threads).forEach { t ->
            Thread({
                try {
                    start.await()
                    repeat(samplesPerThread) { histogram.record(1_000L + t) }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }, "recorder-$t").apply { isDaemon = true }.start()
        }

        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failure.get()).isNull()

        assertThat(histogram.count)
            .describedAs(
                "an unsynchronised growable list silently drops elements when several threads " +
                    "append at once, which quietly biases the p99 the stop condition reads"
            )
            .isEqualTo((threads * samplesPerThread).toLong())
    }
}
