package com.gridgain.demo.datagen.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiveMetricsTest {

    private fun counters(ops: Long, latencyNanos: Long, errors: Long = 0) =
        MetricsRecorder.Counters(ops, latencyNanos, errors)

    @Test
    fun `computes interval tps and average latency from the counter delta`() {
        // 50 ops in a 1s interval, each taking 2ms (2_000_000 ns).
        val prev = counters(ops = 0, latencyNanos = 0)
        val cur = counters(ops = 50, latencyNanos = 50L * 2_000_000L, errors = 3)

        val s = LiveMetrics.computeSnapshot(
            prev = prev, cur = cur, intervalNanos = 1_000_000_000L,
            targetTps = 200.0, runId = "run-1", nowMs = 1234L, active = true,
        )

        assertEquals(50.0, s.observedTps, 1e-9)
        assertEquals(2.0, s.avgLatencyMs, 1e-9)
        assertEquals(50L, s.totalOps)
        assertEquals(3L, s.errorCount)
        assertEquals(200.0, s.targetTps, 1e-9)
        assertEquals("run-1", s.runId)
        assertEquals(1234L, s.updatedAtMs)
        assertEquals(true, s.active)
    }

    @Test
    fun `derives rate over a non-one-second interval`() {
        // 30 ops over a 500ms interval => 60 ops/sec.
        val s = LiveMetrics.computeSnapshot(
            prev = counters(100, 0), cur = counters(130, 30L * 4_000_000L),
            intervalNanos = 500_000_000L, targetTps = 0.0, runId = "r", nowMs = 0L, active = true,
        )
        assertEquals(60.0, s.observedTps, 1e-9)
        assertEquals(4.0, s.avgLatencyMs, 1e-9)
    }

    @Test
    fun `idle interval yields zero rate and zero latency without dividing by zero`() {
        val c = counters(100, 200_000_000L)
        val s = LiveMetrics.computeSnapshot(
            prev = c, cur = c, intervalNanos = 1_000_000_000L,
            targetTps = 0.0, runId = "r", nowMs = 0L, active = true,
        )
        assertEquals(0.0, s.observedTps, 1e-9)
        assertEquals(0.0, s.avgLatencyMs, 1e-9)
    }

    @Test
    fun `zero interval is guarded`() {
        val s = LiveMetrics.computeSnapshot(
            prev = counters(0, 0), cur = counters(10, 10_000_000L), intervalNanos = 0L,
            targetTps = 0.0, runId = "r", nowMs = 0L, active = true,
        )
        assertEquals(0.0, s.observedTps, 1e-9)
    }
}
