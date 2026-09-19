package com.gridgain.demo.datagen.scenario

import java.util.concurrent.atomic.AtomicLong
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
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { nanos ->
        Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
    },
) : RateLimiter {

    /** NaN means "no override" — a sentinel keeps this a single volatile read, so [acquire] can
     *  never observe a torn pairing of a flag and a value. */
    @Volatile private var overrideTps: Double = NO_OVERRIDE

    /** Set once by [release]; never cleared, because the only reason to release is that the run is
     *  ending and must not park again. */
    @Volatile private var released: Boolean = false

    /**
     * The instant the next operation may start. Atomic because with `concurrency > 1` every
     * worker thread reserves from this one cursor: each [acquire] must claim its own slot, or
     * several threads read the same value, wait for the same instant and fire together — the
     * process then runs at a multiple of the requested rate and the limiter silently stops
     * limiting. Reserved with a CAS rather than under [lock] so that the waiting happens outside
     * any lock and the threads genuinely stagger.
     */
    private val nextAllowedNanos = AtomicLong(nanoTime())

    /**
     * Serialises calls into [delegate]. The configured limiters ([ConstantRateLimiter],
     * [RampedRateLimiter], [SteppedRateLimiter]) each carry their own unguarded cursor, so only
     * one thread may be inside one at a time. Deliberately **not** [lock]: the delegate sleeps
     * while holding this, and a [setRate] arriving from the control channel must not queue behind
     * that sleep.
     */
    private val delegateLock = ReentrantLock()

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
            nextAllowedNanos.set(nanoTime())
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
            delegateLock.lock()
            try {
                delegate.acquire()
            } finally {
                delegateLock.unlock()
            }
            return
        }
        if (rate <= 0.0) {
            if (!released) awaitRateChange()
            return
        }
        val interval = (1_000_000_000.0 / rate).toLong()
        // Claim a slot, then wait for it outside the CAS. Retrying on a lost race is correct
        // rather than merely safe: the winner has already moved the cursor past the instant this
        // caller was about to take, so the right answer is to take the next one.
        while (true) {
            val cursor = nextAllowedNanos.get()
            val now = nanoTime()
            val deadline = maxOf(cursor, now)
            if (nextAllowedNanos.compareAndSet(cursor, deadline + interval)) {
                val sleep = deadline - now
                if (sleep > 0) sleeper(sleep)
                return
            }
        }
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
