package com.gridgain.demo.datagen.scenario

import java.time.Duration

interface RateLimiter {
    /** Block until the caller may proceed with the next operation. */
    fun acquire()
}

class ConstantRateLimiter(opsPerSecond: Double) : RateLimiter {
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
}

class RampedRateLimiter(
    private val fromOpsPerSecond: Double,
    private val toOpsPerSecond: Double,
    rampDuration: Duration,
) : RateLimiter {
    private val rampDurationNanos: Long = rampDuration.toNanos()
    private val rampStartedNanos: Long = System.nanoTime()
    private var nextAllowedNanos: Long = rampStartedNanos

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        }
        val effectiveNow = maxOf(nextAllowedNanos, now)
        val elapsed = effectiveNow - rampStartedNanos
        val rate: Double = if (elapsed >= rampDurationNanos) {
            toOpsPerSecond
        } else {
            val t: Double = elapsed.toDouble() / rampDurationNanos
            fromOpsPerSecond + t * (toOpsPerSecond - fromOpsPerSecond)
        }
        val intervalNanos: Long = (1_000_000_000.0 / rate).toLong()
        nextAllowedNanos = effectiveNow + intervalNanos
    }
}
