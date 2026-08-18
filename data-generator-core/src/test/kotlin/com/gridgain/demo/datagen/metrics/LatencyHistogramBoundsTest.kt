package com.gridgain.demo.datagen.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LatencyHistogramBoundsTest {

    @Test
    fun `highestMs of zero is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            LatencyHistogramBounds(highestMs = 0L, significantDigits = 3)
        }

        assertTrue(
            e.message!!.contains("metrics.histogram_highest_ms"),
            "message should name the config key: ${e.message}",
        )
        assertTrue(
            e.message!!.contains("60000"),
            "message should name the recommended value: ${e.message}",
        )
    }

    @Test
    fun `highestMs of one is the minimum accepted value`() {
        val bounds = LatencyHistogramBounds(highestMs = 1L, significantDigits = 3)

        assertEquals(1L, bounds.highestMs)
    }

    @Test
    fun `highestMs above Long MAX_VALUE in microseconds is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            LatencyHistogramBounds(highestMs = Long.MAX_VALUE / 1_000L + 1, significantDigits = 3)
        }

        assertTrue(
            e.message!!.contains("metrics.histogram_highest_ms"),
            "message should name the config key: ${e.message}",
        )
        assertTrue(
            e.message!!.contains("recommended value"),
            "message should say how to fix it, not only what is wrong: ${e.message}",
        )
    }

    @Test
    fun `highestMs at the Long MAX_VALUE microsecond boundary is the maximum accepted value`() {
        val maxHighestMs = Long.MAX_VALUE / 1_000L

        val bounds = LatencyHistogramBounds(highestMs = maxHighestMs, significantDigits = 3)

        assertEquals(maxHighestMs, bounds.highestMs)
    }

    @Test
    fun `significantDigits of zero is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 0)
        }

        assertTrue(
            e.message!!.contains("metrics.histogram_significant_digits"),
            "message should name the config key: ${e.message}",
        )
        assertTrue(
            e.message!!.contains("3 gives"),
            "message should name the recommended value: ${e.message}",
        )
    }

    @Test
    fun `significantDigits of one is the minimum accepted value`() {
        val bounds = LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 1)

        assertEquals(1, bounds.significantDigits)
    }

    @Test
    fun `significantDigits of four is the maximum accepted value`() {
        val bounds = LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 4)

        assertEquals(4, bounds.significantDigits)
    }

    @Test
    fun `significantDigits of five is rejected`() {
        // 5 used to be the accepted maximum; capped at 4 because a 5th digit costs roughly 100x
        // more histogram memory per tick for precision no load test can use. The v6 ops JSONSchema
        // caps at 4 too, and the two must agree.
        val e = assertThrows<IllegalArgumentException> {
            LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 5)
        }

        assertTrue(
            e.message!!.contains("metrics.histogram_significant_digits"),
            "message should name the config key: ${e.message}",
        )
        assertTrue(
            e.message!!.contains("between 1 and 4"),
            "message should name the new upper bound: ${e.message}",
        )
    }

    @Test
    fun `significantDigits of six is rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 6)
        }

        assertTrue(
            e.message!!.contains("metrics.histogram_significant_digits"),
            "message should name the config key: ${e.message}",
        )
    }

    @Test
    fun `highestTrackableMicros converts the millisecond bound to microseconds`() {
        val bounds = LatencyHistogramBounds(highestMs = 60_000L, significantDigits = 3)

        assertEquals(60_000_000L, bounds.highestTrackableMicros)
    }

    @Test
    fun `detached bounds are the recommended one-minute, 3-digit configuration`() {
        val bounds = LatencyHistogramBounds.detached()

        assertEquals(60_000L, bounds.highestMs)
        assertEquals(3, bounds.significantDigits)
    }
}
