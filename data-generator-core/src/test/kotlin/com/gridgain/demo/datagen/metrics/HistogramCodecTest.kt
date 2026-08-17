package com.gridgain.demo.datagen.metrics

import org.HdrHistogram.Histogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HistogramCodecTest {

    private fun histogramOf(vararg micros: Long): Histogram =
        Histogram(60_000_000L, 3).apply { micros.forEach { recordValue(it) } }

    @Test
    fun `round-trips a histogram through the encoded wire form`() {
        val original = histogramOf(1_000L, 2_000L, 3_000L, 250_000L)

        val decoded = HistogramCodec.decode(HistogramCodec.encode(original))

        assertEquals(original.totalCount, decoded.totalCount)
        assertEquals(original.getValueAtPercentile(50.0), decoded.getValueAtPercentile(50.0))
        assertEquals(original.getValueAtPercentile(90.0), decoded.getValueAtPercentile(90.0))
        assertEquals(original.getValueAtPercentile(99.0), decoded.getValueAtPercentile(99.0))
        assertEquals(original.maxValue, decoded.maxValue)
    }

    @Test
    fun `percentiles of the decoded histogram match a known sample set`() {
        // 100 samples: 1ms ninety times, 50ms nine times, 500ms once. p90 sits in the 1ms band's
        // last entry, p99 in the 50ms band, p100 at 500ms. Asserted in microseconds with the 0.1%
        // tolerance three significant digits gives.
        val h = Histogram(60_000_000L, 3)
        repeat(90) { h.recordValue(1_000L) }
        repeat(9) { h.recordValue(50_000L) }
        h.recordValue(500_000L)

        val decoded = HistogramCodec.decode(HistogramCodec.encode(h))

        assertEquals(1_000.0, decoded.getValueAtPercentile(90.0).toDouble(), 1.0)
        assertEquals(50_000.0, decoded.getValueAtPercentile(99.0).toDouble(), 50.0)
        assertEquals(500_000.0, decoded.getValueAtPercentile(100.0).toDouble(), 500.0)
        assertEquals(100L, decoded.totalCount)
    }

    @Test
    fun `an empty histogram round-trips and reports zero total count`() {
        // The legitimate state for a run that recorded nothing. It must encode rather than throw,
        // because the reporter publishes a snapshot on every tick including the first.
        val decoded = HistogramCodec.decode(HistogramCodec.encode(Histogram(60_000_000L, 3)))

        assertEquals(0L, decoded.totalCount)
    }

    @Test
    fun `the encoded form is compact enough to publish every second`() {
        val h = Histogram(60_000_000L, 3)
        repeat(50_000) { h.recordValue(1_000L + it % 5_000) }

        // The design budgets ~1-3 KB per instance per tick. Assert an order of magnitude, not an
        // exact size: the encoding is HdrHistogram's, and pinning bytes would break on an upgrade.
        assertTrue(
            HistogramCodec.encode(h).length < 20_000,
            "encoded histogram must stay small enough for a per-second Kafka publish",
        )
    }

    @Test
    fun `garbage input is rejected rather than silently decoding to an empty histogram`() {
        // The UI logs and skips a malformed instance. That is only safe if decode FAILS loudly on
        // garbage instead of handing back a zero-count histogram it would then report as a real p90.
        assertThrows<IllegalArgumentException> { HistogramCodec.decode("not-base64!!") }
        assertThrows<IllegalArgumentException> { HistogramCodec.decode("YWJjZGVm") }
    }
}
