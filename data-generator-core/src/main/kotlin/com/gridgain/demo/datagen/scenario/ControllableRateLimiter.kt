package com.gridgain.demo.datagen.scenario

import java.util.concurrent.locks.ReentrantLock

/**
 * Wraps the scenario's configured [RateLimiter] so an external command can override its pacing
 * while the run is in flight. With no override the configured schedule (constant, ramped or
 * stepped) runs completely untouched — this decorator only interposes once someone asks it to.
 *
 * Exists so a demo operator can push load up and down from the UI without restarting the run.
 * Commands arrive on the control channel (see `com.gridgain.demo.datagen.control`) on a different
 * thread from the run loop, so the override is `@Volatile` and read fresh on every [acquire];
 * a rate change therefore takes effect on the very next operation.
 *
 * A rate of `0.0` parks the run loop rather than spinning, and a later [setRate] wakes it
 * immediately via the condition — a paused generator must cost nothing and must resume promptly.
 * [release] wakes it too, for when what arrives next is a stop rather than a rate.
 */
class ControllableRateLimiter(
    private val delegate: RateLimiter,
    nanoTime: () -> Long = System::nanoTime,
    sleeper: (Long) -> Unit = PacingWait::await,
) : RateLimiter {

    /** NaN means "no override" — a sentinel keeps this a single volatile read, so [acquire] can
     *  never observe a torn pairing of a flag and a value. */
    @Volatile private var overrideTps: Double = NO_OVERRIDE

    /** Set once by [release]; never cleared, because the only reason to release is that the run is
     *  ending and must not park again. */
    @Volatile private var released: Boolean = false

    /**
     * The override's own pacing cursor. The [delegate] keeps a separate one, which is why
     * [setRate] resets this and [clearOverride] does not touch the delegate's: switching between
     * the two schedules is a change of pacing, not a rewind of the run.
     */
    private val cursor = PacingCursor(nanoTime, sleeper)

    private val lock = ReentrantLock()
    private val rateRaised = lock.newCondition()

    /**
     * Override the configured schedule and pace at [opsPerSecond] instead. `0.0` pauses the run
     * loop. The pacing cursor is reset so a long pause (or a step down from a high rate) cannot
     * produce a catch-up burst when the rate comes back up.
     */
    fun setRate(opsPerSecond: Double) {
        require(opsPerSecond >= 0.0 && !opsPerSecond.isNaN()) {
            "target rate must be zero or positive (0 pauses the generator); received $opsPerSecond. " +
                "Send a non-negative ops/sec value on the control channel."
        }
        lock.lock()
        try {
            overrideTps = opsPerSecond
            cursor.reset()
            rateRaised.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Stop parking, permanently, without changing the reported target rate. Wakes a caller already
     * parked at rate `0.0` and makes every later [acquire] return straight away.
     *
     * Exists for one caller: a [StopSignal] wake-up. A paused generator sits inside [acquire]
     * waiting for a rate that will never come, so the run loop never reaches its own stop check and
     * a stop request on a paused fleet would hang until the JVM was killed. The rate is left alone
     * on purpose — the run is ending, and inventing a rate here would report a target the operator
     * never asked for on the way out.
     */
    fun release() {
        lock.lock()
        try {
            released = true
            rateRaised.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Drop the override and hand pacing back to the configured schedule. */
    fun clearOverride() {
        lock.lock()
        try {
            overrideTps = NO_OVERRIDE
            rateRaised.signalAll()
        } finally {
            lock.unlock()
        }
    }

    override fun acquire() {
        val rate = overrideTps
        if (rate.isNaN()) {
            // Straight through, holding nothing. The delegate reserves from its own [PacingCursor]
            // and is safe for every worker to enter at once.
            //
            // This used to be guarded by a second lock, because the configured limiters carried
            // unguarded cursors. That lock was held across the delegate's wait, so the workers
            // queued on each other instead of on the clock and the process paced as though it had
            // one thread. It is the reason a fleet measured 128 workers holding ~50 operations in
            // flight, and why splitting the same threads across twice as many processes made the
            // generator *faster*.
            delegate.acquire()
            return
        }
        if (rate <= 0.0) {
            if (!released) awaitRateChange()
            return
        }
        val interval = (1_000_000_000.0 / rate).toLong()
        cursor.acquire { interval }
    }

    override fun currentTargetTps(): Double {
        val rate = overrideTps
        return if (rate.isNaN()) delegate.currentTargetTps() else rate
    }

    /** Parks until the override moves off zero (or is cleared, or the limiter is [release]d).
     *  Returns without performing an operation, so the run loop re-evaluates its stop conditions
     *  between pauses. */
    private fun awaitRateChange() {
        lock.lock()
        try {
            while (overrideTps == 0.0 && !released) {
                rateRaised.await()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val NO_OVERRIDE = Double.NaN
    }
}
