package com.gridgain.demo.datagen.scenario

import java.time.Duration

/**
 * The one place a paced wait is performed, so every limiter waits the same way.
 *
 * Exists because `Thread.sleep(millis, nanos)` is the wrong primitive for pacing. It rounds any
 * request below a millisecond **up** to a whole one — measured on JDK 17, `Thread.sleep(0, 500)`
 * takes 1.23 ms, 2,400 times what was asked. That puts a ceiling of roughly 810 operations per
 * second on any waiting thread regardless of the configured rate, which is why the "effectively
 * unlimited" idiom of `ops_per_second: 1000000` never behaved as though it were unlimited.
 */
internal object PacingWait {

    /**
     * Wait approximately [nanos] nanoseconds, never returning early.
     *
     * `LockSupport.parkNanos` measured ~3.9 µs for a 500 ns request against `Thread.sleep`'s
     * 1.23 ms, so it is the primitive. It may still return early — spuriously, or on an unpark —
     * so the deadline is re-checked rather than trusted, and the last stretch is spun out: a park
     * cannot resolve finer than a few microseconds, and at high rates that is the whole interval.
     */
    fun await(nanos: Long) {
        if (nanos <= 0) return
        val deadline = System.nanoTime() + nanos
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > SPIN_THRESHOLD_NANOS) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS)
            } else {
                Thread.onSpinWait()
            }
        }
    }

    /** Below this, park overhead exceeds the wait itself, so spin instead. */
    private const val SPIN_THRESHOLD_NANOS = 50_000L
}

interface RateLimiter {
    /** Block until the caller may proceed with the next operation. */
    fun acquire()

    /**
     * The rate (ops/sec) this limiter is currently pacing to. For scheduled limiters this changes
     * over the life of the run, which is why it is a method rather than a construction-time value:
     * the live-metrics snapshot and the OTel target-rate gauge report it every interval, so a
     * requested-vs-achieved graph tracks a ramp or a step instead of flatlining at the start rate.
     */
    fun currentTargetTps(): Double
}

class ConstantRateLimiter(
    private val opsPerSecond: Double,
    nanoTime: () -> Long = System::nanoTime,
    waiter: (Long) -> Unit = PacingWait::await,
) : RateLimiter {
    private val intervalNanos: Long = (1_000_000_000.0 / opsPerSecond).toLong()
    private val cursor = PacingCursor(nanoTime, waiter)

    override fun acquire() = cursor.acquire { intervalNanos }

    override fun currentTargetTps(): Double = opsPerSecond
}

class RampedRateLimiter(
    private val fromOpsPerSecond: Double,
    private val toOpsPerSecond: Double,
    rampDuration: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
    waiter: (Long) -> Unit = PacingWait::await,
) : RateLimiter {
    private val rampDurationNanos: Long = rampDuration.toNanos()
    private val rampStartedNanos: Long = nanoTime()
    private val cursor = PacingCursor(nanoTime, waiter)

    /** Linear interpolation across the ramp window; holds [toOpsPerSecond] once the window closes. */
    private fun rateAt(nanos: Long): Double {
        val elapsed = nanos - rampStartedNanos
        if (elapsed >= rampDurationNanos) return toOpsPerSecond
        val t: Double = elapsed.toDouble() / rampDurationNanos
        return fromOpsPerSecond + t * (toOpsPerSecond - fromOpsPerSecond)
    }

    override fun acquire() = cursor.acquire { slotNanos ->
        (1_000_000_000.0 / rateAt(slotNanos)).toLong()
    }

    override fun currentTargetTps(): Double = rateAt(nanoTime())
}

data class StepConfig(val rate: Double, val hold: Duration)

class SteppedRateLimiter(
    steps: List<StepConfig>,
    private val nanoTime: () -> Long = System::nanoTime,
    waiter: (Long) -> Unit = PacingWait::await,
) : RateLimiter {

    init {
        if (steps.isEmpty()) {
            throw com.gridgain.demo.datagen.errors.MisconfigurationException(
                "stepped rate limiter requires at least one step; received zero. " +
                "Add at least one entry under 'steps'."
            )
        }
    }

    private data class Boundary(val endNanos: Long, val rate: Double)
    private val rampStartedNanos: Long = nanoTime()
    private val boundaries: List<Boundary>
    private val finalRate: Double
    private val cursor = PacingCursor(nanoTime, waiter)

    init {
        var cumNanos = 0L
        val list = mutableListOf<Boundary>()
        for (s in steps) {
            cumNanos += s.hold.toNanos()
            list.add(Boundary(endNanos = rampStartedNanos + cumNanos, rate = s.rate))
        }
        boundaries = list
        finalRate = steps.last().rate
    }

    /** The step covering [nanos]; the last step's rate is held after the schedule runs out. */
    private fun rateAt(nanos: Long): Double =
        boundaries.firstOrNull { nanos < it.endNanos }?.rate ?: finalRate

    override fun acquire() = cursor.acquire { slotNanos ->
        (1_000_000_000.0 / rateAt(slotNanos)).toLong()
    }

    override fun currentTargetTps(): Double = rateAt(nanoTime())
}
