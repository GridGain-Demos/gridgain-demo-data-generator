package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * One [ControllableRateLimiter] paces the whole process, so with `concurrency > 1` every worker
 * thread calls [ControllableRateLimiter.acquire] on the same instance. That makes the pacing
 * cursor shared mutable state: if threads advance it independently, the process emits a multiple
 * of the requested rate and `targetTpsPerInstance` from the UI stops meaning anything.
 *
 * Asserted against a **frozen clock and a recording sleeper** rather than against wall time.
 * Wall-clock timing cannot see this bug: the interval at any testable rate is well under
 * `Thread.sleep`'s millisecond granularity, so the OS rounds every sleep up to the same floor and
 * a racing limiter finishes in the same elapsed time as a correct one.
 *
 * With the clock frozen at T, a correct limiter hands out one distinct deadline per acquire —
 * T, T+i, T+2i, … — so the sleeps it requests are exactly {i, 2i, …, (n-1)i} (the first acquire
 * is already due and sleeps not at all). A limiter whose threads read a stale cursor hands the
 * same deadline to several callers, which shows up immediately as duplicate sleeps and a maximum
 * far below (n-1)i.
 */
class ControllableRateLimiterConcurrencyTest {

    private val threads = 8
    private val acquiresPerThread = 25
    private val ratePerSecond = 2_000.0
    private val intervalNanos = (1_000_000_000.0 / ratePerSecond).toLong()   // 500_000

    @Test
    fun `every thread sharing a limiter reserves its own distinct slot`() {
        val frozenNow = 1_000_000_000L
        val requestedSleeps = ConcurrentLinkedQueue<Long>()
        val limiter = ControllableRateLimiter(
            delegate = ConstantRateLimiter(ratePerSecond),
            nanoTime = { frozenNow },
            sleeper = { nanos -> requestedSleeps.add(nanos) },
        )
        limiter.setRate(ratePerSecond)

        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        (0 until threads).forEach { t ->
            Thread({
                try {
                    start.await()
                    repeat(acquiresPerThread) { limiter.acquire() }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }, "acquirer-$t").apply { isDaemon = true }.start()
        }

        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failure.get()).isNull()

        val total = threads * acquiresPerThread
        val expected = (1 until total).map { it * intervalNanos }

        assertThat(requestedSleeps.sorted())
            .describedAs(
                "%d acquires must reserve %d distinct slots one interval (%dns) apart; duplicates " +
                    "mean two threads were handed the same deadline and the process ran fast",
                total, total, intervalNanos,
            )
            .containsExactlyElementsOf(expected)
    }
}
