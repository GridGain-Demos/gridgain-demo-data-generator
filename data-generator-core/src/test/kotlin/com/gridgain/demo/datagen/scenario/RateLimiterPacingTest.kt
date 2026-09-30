package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * The pacing path taken when **nobody has sent a control command** — which is every benchmark run,
 * and which until now was the only path with no concurrency test of its own.
 *
 * [ControllableRateLimiterConcurrencyTest] calls `setRate` first and therefore only ever exercises
 * the override branch. That branch was already correct: it reserves a slot with a CAS and waits
 * outside any lock. The default branch was not, and the gap was invisible because no test crossed
 * it. The consequence, measured on a four-machine fleet, was 128 worker threads holding roughly 50
 * operations in flight, and the same 128 threads split across twice as many processes holding 112
 * — a limiter that got faster the more you fragmented it.
 *
 * Two independent defects live here, so there are two independent tests:
 *
 *  1. the wrapper serialised every worker through one lock **held across the delegate's sleep**;
 *  2. the sleep itself was `Thread.sleep(0, n)`, which on JDK 17 rounds any sub-millisecond request
 *     up to ~1.2 ms — a hard ceiling of roughly 810 operations per second per waiter, whatever rate
 *     was asked for.
 *
 * Either one alone caps a generator far below the cluster's capability, and neither is visible in a
 * throughput figure: the run simply reports a lower number and looks like a slow cluster.
 */
class RateLimiterPacingTest {

    // -----------------------------------------------------------------------
    // 1. The wrapper must not serialise workers through the delegate
    // -----------------------------------------------------------------------

    /**
     * A delegate that blocks, and counts how many callers are inside it at once.
     *
     * Blocking is what makes the defect observable. A lock held only across bookkeeping is almost
     * free; a lock held across a wait is a queue, and every worker behind it is stalled for reasons
     * that have nothing to do with the cluster being measured.
     */
    private class OccupancyCountingLimiter(private val holdMillis: Long) : RateLimiter {
        val inside = AtomicInteger(0)
        val peakInside = AtomicInteger(0)
        val calls = AtomicInteger(0)

        override fun acquire() {
            val now = inside.incrementAndGet()
            peakInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(holdMillis)
                calls.incrementAndGet()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun currentTargetTps(): Double = 1_000.0
    }

    @Test
    fun `workers on the default path wait concurrently rather than queueing behind one another`() {
        val threads = 8
        val delegate = OccupancyCountingLimiter(holdMillis = 25)
        // Deliberately no setRate: this is the path a run takes until an operator intervenes.
        val limiter = ControllableRateLimiter(delegate = delegate)

        runConcurrently(threads = threads, acquiresPerThread = 4) { limiter.acquire() }

        assertThat(delegate.calls.get()).isEqualTo(threads * 4)
        assertThat(delegate.peakInside.get())
            .describedAs(
                "all %d workers must be able to be waiting at once; a peak of 1 means the wrapper " +
                    "held a lock across the wait and the process paced as though single-threaded",
                threads,
            )
            .isGreaterThan(1)
    }

    // -----------------------------------------------------------------------
    // 2. The delegates must be thread-safe on their own
    // -----------------------------------------------------------------------

    /**
     * Once the wrapper stops serialising, the delegate is genuinely concurrent, and its pacing
     * cursor has to be. Asserted against a frozen clock and a recording waiter for the reason
     * [ControllableRateLimiterConcurrencyTest] gives: wall-clock timing cannot see a racing cursor,
     * because every sleep is rounded up to the same floor and a racing limiter finishes in the same
     * elapsed time as a correct one.
     *
     * With the clock frozen at T a correct cursor hands out T, T+i, T+2i, …, so the waits it asks
     * for are exactly {i, 2i, …, (n-1)i} — the first slot is already due and waits not at all. A
     * racing cursor hands the same deadline to several callers, which shows as duplicates.
     */
    @Test
    fun `a constant limiter shared by many threads reserves one distinct slot per acquire`() {
        val threads = 8
        val acquiresPerThread = 25
        val ratePerSecond = 2_000.0
        val intervalNanos = 500_000L
        val frozenNow = 1_000_000_000L

        val requestedWaits = ConcurrentLinkedQueue<Long>()
        val limiter = ConstantRateLimiter(
            opsPerSecond = ratePerSecond,
            nanoTime = { frozenNow },
            waiter = { nanos -> requestedWaits.add(nanos) },
        )

        runConcurrently(threads, acquiresPerThread) { limiter.acquire() }

        val total = threads * acquiresPerThread
        assertThat(requestedWaits.sorted())
            .describedAs(
                "%d acquires must reserve %d distinct slots one interval (%dns) apart; a duplicate " +
                    "means two threads were handed the same deadline and the process ran fast",
                total, total, intervalNanos,
            )
            .containsExactlyElementsOf((1 until total).map { it * intervalNanos })
    }

    // -----------------------------------------------------------------------
    // 3. Sub-millisecond waits must actually be sub-millisecond
    // -----------------------------------------------------------------------

    /**
     * `Thread.sleep(0, n)` rounds up to a whole millisecond, so a 500 ns pacing interval became a
     * measured 1.23 ms on JDK 17 — 2,400 times what was asked for. That is a ceiling of ~810 ops/s
     * per waiting thread no matter what `ops_per_second` says, and it is why the "effectively
     * unlimited" idiom (`ops_per_second: 1000000`) never behaved as though it were unlimited.
     *
     * The bar here is deliberately loose. The point is not that the wait is exact — no JVM wait is —
     * but that it is on the right side of a millisecond.
     */
    @Test
    fun `the default waiter honours a sub-millisecond request`() {
        val requestNanos = 200_000L          // 0.2 ms
        val samples = 50

        repeat(10) { PacingWait.await(requestNanos) }      // let the JIT settle

        val start = System.nanoTime()
        repeat(samples) { PacingWait.await(requestNanos) }
        val averageNanos = (System.nanoTime() - start) / samples

        assertThat(averageNanos)
            .describedAs(
                "a %dns wait averaged %dns; Thread.sleep(0, n) rounds every sub-millisecond wait up " +
                    "to ~1.2ms, which caps a paced worker at roughly 810 ops/s",
                requestNanos, averageNanos,
            )
            .isLessThan(1_000_000L)
    }

    @Test
    fun `the default waiter does not return early`() {
        val requestNanos = 2_000_000L        // 2 ms, comfortably above timer granularity
        val start = System.nanoTime()
        PacingWait.await(requestNanos)
        val elapsed = System.nanoTime() - start

        assertThat(elapsed)
            .describedAs("a limiter that returns early does not limit")
            .isGreaterThanOrEqualTo(requestNanos)
    }

    // -----------------------------------------------------------------------

    private fun runConcurrently(threads: Int, acquiresPerThread: Int, body: () -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        (0 until threads).forEach { t ->
            Thread({
                try {
                    start.await()
                    repeat(acquiresPerThread) { body() }
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
    }
}
