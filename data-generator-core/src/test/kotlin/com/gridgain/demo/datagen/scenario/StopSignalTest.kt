package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class StopSignalTest {

    @Test
    fun `a fresh signal is not raised and has no reason`() {
        val signal = StopSignal()

        assertThat(signal.isRaised).isFalse()
        assertThat(signal.reason()).isNull()
    }

    @Test
    fun `raising records the reason`() {
        val signal = StopSignal()

        assertThat(signal.raise("SIGTERM")).isTrue()

        assertThat(signal.isRaised).isTrue()
        assertThat(signal.reason()).isEqualTo("SIGTERM")
    }

    @Test
    fun `the first reason wins and a later raise reports that it lost`() {
        val signal = StopSignal()

        signal.raise("operator stop command")
        val second = signal.raise("SIGTERM")

        assertThat(second).isFalse()
        assertThat(signal.reason())
            .describedAs("the cause of the stop is the first thing that asked for it")
            .isEqualTo("operator stop command")
    }

    @Test
    fun `a blank reason is refused because it becomes the run's stop_reason`() {
        assertThatThrownBy { StopSignal().raise("  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("stop_reason")
    }

    @Test
    fun `a registered wake-up runs when the signal is raised`() {
        val signal = StopSignal()
        var woken = 0
        signal.onRaise { woken++ }

        signal.raise("SIGTERM")

        assertThat(woken).isEqualTo(1)
    }

    @Test
    fun `only the first raise runs the wake-ups`() {
        val signal = StopSignal()
        var woken = 0
        signal.onRaise { woken++ }

        signal.raise("first")
        signal.raise("second")

        assertThat(woken).isEqualTo(1)
    }

    @Test
    fun `a wake-up registered after the raise still runs, so it cannot miss the signal`() {
        val signal = StopSignal()
        signal.raise("SIGTERM")

        var woken = 0
        signal.onRaise { woken++ }

        assertThat(woken).isEqualTo(1)
    }

    @Test
    fun `a throwing wake-up does not stop the others or fail the raise`() {
        val signal = StopSignal()
        var woken = 0
        signal.onRaise { throw IllegalStateException("boom") }
        signal.onRaise { woken++ }

        assertThat(signal.raise("SIGTERM")).isTrue()

        assertThat(woken)
            .describedAs("a shutdown hook has nowhere to report a wake-up failure to")
            .isEqualTo(1)
    }
}
