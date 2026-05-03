package com.gridgain.demo.datagen.scenario

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
