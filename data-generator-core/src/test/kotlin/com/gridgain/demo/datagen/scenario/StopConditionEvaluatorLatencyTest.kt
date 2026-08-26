package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class StopConditionEvaluatorLatencyTest {

    @Test
    fun `latency p99 above threshold triggers stop`() {
        val e = StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.001S")), StopSignal()) // 1ms
        // 98 fast samples + 2 slow samples: with 100 total, p99 rank = floor(0.99*99)=98 (0-based),
        // which lands on the slow sample, exceeding the 1ms threshold.
        repeat(98) { e.recordOutcome(success = true, latencyNanos = 100_000L) }
        repeat(2) { e.recordOutcome(success = true, latencyNanos = 100_000_000L) }
        val reason = e.shouldStop()
        assertThat(reason).isNotNull
        assertThat(reason!!).contains("latency_p99")
    }

    @Test
    fun `latency p99 below threshold does not trigger`() {
        val e = StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.1S")), StopSignal()) // 100ms
        repeat(100) { e.recordOutcome(success = true, latencyNanos = 1_000_000L) }
        assertThat(e.shouldStop()).isNull()
    }
}
