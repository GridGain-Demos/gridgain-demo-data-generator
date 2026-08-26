package com.gridgain.demo.datagen.scenario

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControllableRateLimiterTest {

    /** Records delegation and lets a test assert the configured schedule still runs untouched. */
    private class RecordingRateLimiter(private val rate: Double) : RateLimiter {
        var acquireCount = 0
        override fun acquire() {
            acquireCount++
        }
        override fun currentTargetTps(): Double = rate
    }

    /** Deterministic clock: sleeping advances time, so pacing is testable without wall-clock waits. */
    private class FakeClock {
        var nanos = 0L
        val sleeps = mutableListOf<Long>()
        fun now(): Long = nanos
        fun sleep(n: Long) {
            sleeps += n
            nanos += n
        }
    }

    @Test
    fun `without an override acquire delegates to the configured limiter`() {
        val delegate = RecordingRateLimiter(50.0)
        val limiter = ControllableRateLimiter(delegate)

        repeat(3) { limiter.acquire() }

        assertEquals(3, delegate.acquireCount)
    }

    @Test
    fun `without an override the target rate is the configured limiter's`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))

        assertEquals(50.0, limiter.currentTargetTps(), 1e-9)
    }

    @Test
    fun `setRate overrides the reported target rate`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))

        limiter.setRate(200.0)

        assertEquals(200.0, limiter.currentTargetTps(), 1e-9)
    }

    @Test
    fun `an override takes over pacing and stops delegating`() {
        val delegate = RecordingRateLimiter(50.0)
        val clock = FakeClock()
        val limiter = ControllableRateLimiter(delegate, clock::now, clock::sleep)

        limiter.setRate(100.0) // 100 ops/sec => 10ms between ops
        repeat(4) { limiter.acquire() }

        assertEquals(0, delegate.acquireCount, "override must not fall through to the delegate")
        // First acquire is immediate; the next three each wait one 10ms interval.
        assertEquals(listOf(10_000_000L, 10_000_000L, 10_000_000L), clock.sleeps)
    }

    @Test
    fun `raising the rate mid-run shortens the interval on the next acquire`() {
        val clock = FakeClock()
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0), clock::now, clock::sleep)

        limiter.setRate(100.0) // 10ms spacing
        limiter.acquire()
        limiter.acquire()
        limiter.setRate(500.0) // 2ms spacing
        // setRate resets the pacing cursor to "now" so a rate change can't produce a catch-up
        // burst — hence the first acquire after it proceeds immediately, and the next one waits
        // the new, shorter interval.
        limiter.acquire()
        limiter.acquire()

        assertEquals(listOf(10_000_000L, 2_000_000L), clock.sleeps)
    }

    @Test
    fun `clearOverride restores the configured schedule and its target rate`() {
        val delegate = RecordingRateLimiter(50.0)
        val limiter = ControllableRateLimiter(delegate)

        limiter.setRate(200.0)
        limiter.clearOverride()
        limiter.acquire()

        assertEquals(50.0, limiter.currentTargetTps(), 1e-9)
        assertEquals(1, delegate.acquireCount)
    }

    @Test
    fun `a zero rate parks the caller until the rate is raised again`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))
        limiter.setRate(0.0)

        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            limiter.acquire()
            returned.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue(entered.await(2, TimeUnit.SECONDS), "worker never started")
        assertTrue(!returned.await(200, TimeUnit.MILLISECONDS), "acquire should park while the rate is zero")

        limiter.setRate(100.0)

        assertTrue(returned.await(2, TimeUnit.SECONDS), "raising the rate must wake the parked caller")
        worker.join(1_000)
    }

    @Test
    fun `release wakes a caller parked at zero without inventing a rate`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))
        limiter.setRate(0.0)

        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            limiter.acquire()
            returned.countDown()
        }.apply { isDaemon = true; start() }

        assertTrue(entered.await(2, TimeUnit.SECONDS), "worker never started")
        assertTrue(!returned.await(200, TimeUnit.MILLISECONDS), "acquire should park while the rate is zero")

        limiter.release()

        assertTrue(returned.await(2, TimeUnit.SECONDS), "release must wake the parked caller so it can stop")
        assertEquals(0.0, limiter.currentTargetTps(), 1e-9,
            "release ends the run; it must not report a target the operator never asked for")
        worker.join(1_000)
    }

    @Test
    fun `after release a zero rate no longer parks`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))
        limiter.setRate(0.0)
        limiter.release()

        val returned = CountDownLatch(1)
        Thread { repeat(3) { limiter.acquire() }; returned.countDown() }
            .apply { isDaemon = true; start() }

        assertTrue(returned.await(2, TimeUnit.SECONDS),
            "a released limiter must never park again — the run is ending")
    }

    @Test
    fun `a zero rate reports zero as the target`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))

        limiter.setRate(0.0)

        assertEquals(0.0, limiter.currentTargetTps(), 1e-9)
    }

    @Test
    fun `a negative rate is rejected`() {
        val limiter = ControllableRateLimiter(RecordingRateLimiter(50.0))

        val e = kotlin.runCatching { limiter.setRate(-1.0) }.exceptionOrNull()

        assertTrue(e is IllegalArgumentException, "expected IllegalArgumentException, got $e")
        assertTrue(e.message!!.contains("-1"), "message should name the offending value: ${e.message}")
    }
}
