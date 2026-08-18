package com.gridgain.demo.datagen.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsRecorderHistogramTest {

    private fun recorder() = MetricsRecorder(
        LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 3)
    )

    @Test
    fun `records every op into the whole-run histogram on the same call as the counters`() {
        val r = recorder()
        repeat(10) { r.record(latencyNanos = 2_000_000L, success = true) } // 2ms

        val h = r.histogramSnapshot()

        assertEquals(10L, h.totalCount)
        assertEquals(10L, r.counters().ops, "counters and histogram must see the same ops")
        // JUnit 5 has no delta-based assertEquals overload for Long (only Float/Double), so the
        // percentile comparisons below compare as Double against the same tolerance in microseconds.
        assertEquals(2_000.0, h.getValueAtPercentile(50.0).toDouble(), 2.0, "2ms == 2000 microseconds")
    }

    @Test
    fun `errors are recorded in the histogram too - a failed op still took time`() {
        val r = recorder()
        r.record(latencyNanos = 5_000_000L, success = false)

        assertEquals(1L, r.histogramSnapshot().totalCount)
        assertEquals(1L, r.counters().errors)
    }

    @Test
    fun `percentiles over a known sample set match the samples`() {
        val r = recorder()
        repeat(90) { r.record(1_000_000L, success = true) }  // 1ms
        repeat(9) { r.record(50_000_000L, success = true) }  // 50ms
        r.record(500_000_000L, success = true)               // 500ms

        val h = r.histogramSnapshot()

        assertEquals(1_000.0, h.getValueAtPercentile(90.0).toDouble(), 1.0)
        assertEquals(50_000.0, h.getValueAtPercentile(99.0).toDouble(), 50.0)
        assertEquals(500_000.0, h.getValueAtPercentile(100.0).toDouble(), 500.0)
    }

    @Test
    fun `an operation slower than the configured bound is clamped, not dropped and not fatal`() {
        // A histogram is bounded by construction. Recording past the bound throws in HdrHistogram,
        // and a generator that dies because one operation was slow is worse than a p99 pinned at
        // the ceiling — which is itself the honest reading of "slower than we can measure".
        val r = MetricsRecorder(LatencyHistogramBounds(highestMs = 100L, significantDigits = 3))

        r.record(latencyNanos = 10_000_000_000L, success = true) // 10s against a 100ms bound

        val h = r.histogramSnapshot()
        assertEquals(1L, h.totalCount, "the op must still be counted")
        assertEquals(100_000.0, h.getValueAtPercentile(100.0).toDouble(), 100.0, "clamped to the 100ms bound")
        assertEquals(1L, r.counters().ops)
    }

    @Test
    fun `the snapshot is a copy - later ops do not change a histogram already taken`() {
        // The reporter encodes a snapshot on its own thread while workers keep recording. If the
        // snapshot aliased the live histogram, an encode could observe a torn state.
        val r = recorder()
        r.record(1_000_000L, success = true)

        val taken = r.histogramSnapshot()
        r.record(1_000_000L, success = true)

        assertEquals(1L, taken.totalCount, "an already-taken snapshot must not grow")
        assertEquals(2L, r.histogramSnapshot().totalCount)
    }

    @Test
    fun `a fresh recorder yields an empty histogram rather than throwing`() {
        assertTrue(recorder().histogramSnapshot().totalCount == 0L)
    }
}
