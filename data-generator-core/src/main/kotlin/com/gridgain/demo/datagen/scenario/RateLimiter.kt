package com.gridgain.demo.datagen.scenario

import java.time.Duration

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

class ConstantRateLimiter(private val opsPerSecond: Double) : RateLimiter {
    private val intervalNanos: Long = (1_000_000_000.0 / opsPerSecond).toLong()
    private var nextAllowedNanos: Long = System.nanoTime()

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            val ms = sleep / 1_000_000
            val ns = (sleep % 1_000_000).toInt()
            Thread.sleep(ms, ns)
        }
        nextAllowedNanos = maxOf(nextAllowedNanos, now) + intervalNanos
    }

    override fun currentTargetTps(): Double = opsPerSecond
}

class RampedRateLimiter(
    private val fromOpsPerSecond: Double,
    private val toOpsPerSecond: Double,
    rampDuration: Duration,
) : RateLimiter {
    private val rampDurationNanos: Long = rampDuration.toNanos()
    private val rampStartedNanos: Long = System.nanoTime()
    private var nextAllowedNanos: Long = rampStartedNanos

    /** Linear interpolation across the ramp window; holds [toOpsPerSecond] once the window closes. */
    private fun rateAt(nanos: Long): Double {
        val elapsed = nanos - rampStartedNanos
        if (elapsed >= rampDurationNanos) return toOpsPerSecond
        val t: Double = elapsed.toDouble() / rampDurationNanos
        return fromOpsPerSecond + t * (toOpsPerSecond - fromOpsPerSecond)
    }

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        }
        val effectiveNow = maxOf(nextAllowedNanos, now)
        val intervalNanos: Long = (1_000_000_000.0 / rateAt(effectiveNow)).toLong()
        nextAllowedNanos = effectiveNow + intervalNanos
    }

    override fun currentTargetTps(): Double = rateAt(System.nanoTime())
}

data class StepConfig(val rate: Double, val hold: Duration)

class SteppedRateLimiter(steps: List<StepConfig>) : RateLimiter {

    init {
        if (steps.isEmpty()) {
            throw com.gridgain.demo.datagen.errors.MisconfigurationException(
                "stepped rate limiter requires at least one step; received zero. " +
                "Add at least one entry under 'steps'."
            )
        }
    }

    private data class Boundary(val endNanos: Long, val rate: Double)
    private val rampStartedNanos: Long = System.nanoTime()
    private val boundaries: List<Boundary>
    private val finalRate: Double
    private var nextAllowedNanos: Long = rampStartedNanos

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

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        }
        val effectiveNow = maxOf(nextAllowedNanos, now)
        val intervalNanos: Long = (1_000_000_000.0 / rateAt(effectiveNow)).toLong()
        nextAllowedNanos = effectiveNow + intervalNanos
    }

    override fun currentTargetTps(): Double = rateAt(System.nanoTime())
}
