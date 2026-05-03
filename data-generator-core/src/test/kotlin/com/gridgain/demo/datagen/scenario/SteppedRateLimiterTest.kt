package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class SteppedRateLimiterTest {

    @Test
    fun `walks two steps and holds at the last`() {
        val limiter = SteppedRateLimiter(
            steps = listOf(
                StepConfig(rate = 100.0, hold = Duration.ofMillis(100)),
                StepConfig(rate = 500.0, hold = Duration.ofMillis(50)),
            ),
        )
        val start = System.nanoTime()
        var count = 0
        while ((System.nanoTime() - start) < 200_000_000L) {
            limiter.acquire()
            count++
        }
        assertThat(count).isBetween(40, 90)
    }

    @Test
    fun `single step behaves like a constant rate`() {
        val limiter = SteppedRateLimiter(
            steps = listOf(StepConfig(rate = 200.0, hold = Duration.ofSeconds(10))),
        )
        val start = System.nanoTime()
        repeat(50) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isBetween(200L, 400L)
    }
}
