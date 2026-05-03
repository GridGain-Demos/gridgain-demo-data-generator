package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class RampedRateLimiterTest {

    @Test
    fun `ramp from 50 to 200 over 200ms then hold yields about 30-50 ops in 250ms`() {
        val limiter = RampedRateLimiter(
            fromOpsPerSecond = 50.0,
            toOpsPerSecond = 200.0,
            rampDuration = Duration.ofMillis(200),
        )
        val start = System.nanoTime()
        var count = 0
        while ((System.nanoTime() - start) < 250_000_000L) {
            limiter.acquire()
            count++
        }
        assertThat(count).isBetween(20, 60)
    }

    @Test
    fun `after the ramp window the rate is steady at to`() {
        val limiter = RampedRateLimiter(
            fromOpsPerSecond = 10.0,
            toOpsPerSecond = 1000.0,
            rampDuration = Duration.ofMillis(50),
        )
        Thread.sleep(60)
        val start = System.nanoTime()
        repeat(50) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isBetween(40L, 200L)
    }
}
