package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class StopConditionEvaluatorTest {

    @Test
    fun `error rate below threshold does not trigger`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)))
        repeat(100) { e.recordOutcome(success = true) }
        assertThat(e.shouldStop()).isNull()
    }

    @Test
    fun `error rate above threshold triggers stop with reason`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)))
        repeat(100) { e.recordOutcome(success = true) }
        repeat(20) { e.recordOutcome(success = false) }
        val reason = e.shouldStop()
        assertThat(reason).isNotNull
        assertThat(reason!!).contains("error_rate")
    }

    @Test
    fun `unsupported stop condition kind is rejected at construction`() {
        assertThatThrownBy { StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.1S"))) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("latency_p99_above")
            .hasMessageContaining("Plan 5")

        assertThatThrownBy { StopConditionEvaluator(listOf(ExternalSignalStopSpec())) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("external_signal")
    }
}
