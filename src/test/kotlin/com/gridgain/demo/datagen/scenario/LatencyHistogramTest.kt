package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class LatencyHistogramTest {

    @Test
    fun quantileIsNullWhenNoSamples() {
        val h = LatencyHistogram()
        assertThat(h.quantile(0.99)).isNull()
    }

    @Test
    fun p99Of1To100IsApproximately99() {
        val h = LatencyHistogram()
        for (i in 1L..100L) h.record(i)
        assertThat(h.quantile(0.99)).isEqualTo(99L)
    }

    @Test
    fun p999OfUniform1To1000IsApproximately999() {
        val h = LatencyHistogram()
        for (i in 1L..1000L) h.record(i)
        assertThat(h.quantile(0.999)).isEqualTo(999L)
    }

    @Test
    fun quantilePIsRejectedWhenOutOfRange() {
        val h = LatencyHistogram()
        h.record(1L)
        assertThatThrownBy { h.quantile(1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
