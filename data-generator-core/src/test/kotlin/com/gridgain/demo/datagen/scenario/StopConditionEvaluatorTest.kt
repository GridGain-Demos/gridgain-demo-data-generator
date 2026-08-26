package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class StopConditionEvaluatorTest {

    @Test
    fun `error rate below threshold does not trigger`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)), StopSignal())
        repeat(100) { e.recordOutcome(success = true) }
        assertThat(e.shouldStop()).isNull()
    }

    @Test
    fun `error rate above threshold triggers stop with reason`() {
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.05)), StopSignal())
        repeat(100) { e.recordOutcome(success = true) }
        repeat(20) { e.recordOutcome(success = false) }
        val reason = e.shouldStop()
        assertThat(reason).isNotNull
        assertThat(reason!!).contains("error_rate")
    }

    @Test
    fun `external_signal does not trigger while the signal is unraised`() {
        val e = StopConditionEvaluator(listOf(ExternalSignalStopSpec()), StopSignal())

        repeat(500) { e.recordOutcome(success = true) }

        assertThat(e.shouldStop()).isNull()
    }

    @Test
    fun `external_signal triggers once the signal is raised, naming the reason`() {
        val signal = StopSignal()
        val e = StopConditionEvaluator(listOf(ExternalSignalStopSpec()), signal)
        repeat(100) { e.recordOutcome(success = true) }

        signal.raise("operator stop command on the control channel")

        assertThat(e.shouldStop())
            .isEqualTo("external_signal raised: operator stop command on the control channel")
    }

    @Test
    fun `external_signal triggers before the hundred-sample gate the thresholds wait for`() {
        val signal = StopSignal()
        val e = StopConditionEvaluator(listOf(ExternalSignalStopSpec()), signal)
        // One operation in. At a low rate the 100th could be minutes away — far past any grace
        // period — so an operator stop must not queue behind the sample-size gate.
        e.recordOutcome(success = true)

        signal.raise("SIGTERM")

        assertThat(e.shouldStop()).isEqualTo("external_signal raised: SIGTERM")
    }

    @Test
    fun `a raised signal does not trigger a scenario that did not declare external_signal`() {
        val signal = StopSignal()
        val e = StopConditionEvaluator(listOf(ErrorRateStopSpec(0.5)), signal)
        repeat(100) { e.recordOutcome(success = true) }

        signal.raise("SIGTERM")

        assertThat(e.shouldStop())
            .describedAs("the run loop reports the signal itself; the evaluator only reports conditions")
            .isNull()
    }
}
