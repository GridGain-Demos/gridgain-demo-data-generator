package com.gridgain.demo.datagen.metrics

import org.HdrHistogram.Histogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class HistogramCodecTest {

    private fun newHistogram(): Histogram =
        Histogram(RECOMMENDED_HIGHEST_TRACKABLE_MICROS, RECOMMENDED_SIGNIFICANT_DIGITS)

    private fun histogramOf(vararg micros: Long): Histogram =
        newHistogram().apply { micros.forEach { recordValue(it) } }

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
        val h = newHistogram()
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
        val decoded = HistogramCodec.decode(HistogramCodec.encode(newHistogram()))

        assertEquals(0L, decoded.totalCount)
    }

    /**
     * Log-uniform across the full trackable range: with enough samples this touches every bucket
     * the configured bounds allow, and — unlike a uniform fill — leaves per-bucket counts that vary
     * instead of repeating. A uniform fill compresses to a few hundred bytes regardless of sample
     * count, because HdrHistogram's deflate step crushes the repetition; that made the original
     * version of this test unable to catch a real regression. The seed is fixed so the two size
     * tests below are deterministic.
     */
    private fun saturatingFill(samples: Int, seed: Long = 42L): Histogram {
        val h = newHistogram()
        val rnd = Random(seed)
        val lnLow = ln(1.0)
        val lnHigh = ln(RECOMMENDED_HIGHEST_TRACKABLE_MICROS.toDouble())
        repeat(samples) {
            val value = exp(lnLow + rnd.nextDouble() * (lnHigh - lnLow))
                .toLong()
                .coerceIn(1L, RECOMMENDED_HIGHEST_TRACKABLE_MICROS)
            h.recordValue(value)
        }
        return h
    }

    @Test
    fun `the encoded form stays within the measured ceiling for a bucket-saturating histogram`() {
        // 1M log-uniform samples saturate all 17,190 buckets this config (60s ceiling, 3 significant
        // digits) allows -- confirmed by counting non-zero buckets against Histogram#allValues()
        // when this test was written. That is the true worst case, not a typical one: real latencies
        // cluster, so a real run's histogram compresses far better than this. Measured encoded
        // length at this fill: 17,604 chars (~17.2 KB). The threshold below has headroom for a
        // compression-library version bump, not for a design change -- if this test starts failing,
        // re-measure before raising it.
        val encoded = HistogramCodec.encode(saturatingFill(samples = 1_000_000))

        assertTrue(
            encoded.length < 22_000,
            "encoded histogram exceeded the measured worst-case ceiling: ${encoded.length} chars",
        )
    }

    @Test
    fun `cost is bounded by bucket count, not by how many samples were recorded`() {
        // Once every bucket is already touched (at 1M samples, per the ceiling test above), adding
        // 9x more samples to the SAME buckets should grow the encoding only a little: the per-bucket
        // counts need marginally more bytes to varint-encode, but no new buckets appear. Measured:
        // 17,604 chars at 1M samples, 29,172 chars at 10M -- about 1.66x for a 10x jump in samples.
        // This is the invariant the design actually depends on; a codec that scaled with run length
        // instead of bucket count would blow through 3x long before 10x.
        val smaller = HistogramCodec.encode(saturatingFill(samples = 1_000_000)).length
        val larger = HistogramCodec.encode(saturatingFill(samples = 10_000_000)).length

        assertTrue(
            larger < smaller * 3,
            "encoding grew more than 3x between 1M and 10M samples of the same buckets " +
                "($smaller -> $larger chars), but cost should scale with bucket count and " +
                "significant digits, not with run length",
        )
    }

    @Test
    fun `garbage input is rejected rather than silently decoding to an empty histogram`() {
        // The UI logs and skips a malformed instance. That is only safe if decode FAILS loudly on
        // garbage instead of handing back a zero-count histogram it would then report as a real p90.
        // Each branch asserts a message substring distinct to that branch, so a bug that empties or
        // swaps the two messages does not pass silently.
        // Both messages happen to mention "base64" and "HdrHistogram compressed encoding", so those
        // words alone would not catch a swap. "not valid base64" and "Likely causes" each appear in
        // exactly one of the two messages.
        val notBase64 = assertThrows<IllegalArgumentException> { HistogramCodec.decode("not-base64!!") }
        assertTrue(
            notBase64.message!!.contains("not valid base64"),
            "message should identify the base64 problem: ${notBase64.message}",
        )

        val notAHistogram = assertThrows<IllegalArgumentException> { HistogramCodec.decode("YWJjZGVm") }
        assertTrue(
            notAHistogram.message!!.contains("Likely causes"),
            "message should identify the decoding problem: ${notAHistogram.message}",
        )
    }

    private companion object {
        // The recommended bounds from ops.yaml's metrics block: a 60s ceiling at 3 significant
        // digits (0.1% error). Every test in this file builds histograms at these bounds, because
        // the codec's behavior -- particularly its encoded size -- depends on them.
        const val RECOMMENDED_HIGHEST_TRACKABLE_MICROS = 60_000_000L
        const val RECOMMENDED_SIGNIFICANT_DIGITS = 3
    }
}
