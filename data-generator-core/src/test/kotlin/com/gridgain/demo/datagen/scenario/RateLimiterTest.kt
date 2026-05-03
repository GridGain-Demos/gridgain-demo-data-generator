package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class RateLimiterTest {

    @Test
    fun `100 ops at constant rate of 200 takes about 500ms`() {
        val limiter = ConstantRateLimiter(opsPerSecond = 200.0)
        val start = System.nanoTime()
        repeat(100) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isBetween(400L, 700L)
    }

    @Test
    fun `first acquire returns immediately`() {
        val limiter = ConstantRateLimiter(opsPerSecond = 100.0)
        val start = System.nanoTime()
        limiter.acquire()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isLessThan(50L)
    }
}
