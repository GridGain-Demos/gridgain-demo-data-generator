package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ExternalSignalControlValidatorTest {

    private fun data() = DataConfig(
        CURRENT_DATA_SCHEMA_VERSION,
        listOf(SchemaSpec("customer", 0.0,
            listOf(ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))))),
    )

    private fun scenario(
        name: String,
        duration: DurationSpec,
        stopConditions: List<StopConditionSpec>,
    ) = ScenarioSpec(
        name = name, rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0), duration = duration,
        stopConditions = stopConditions, readRatio = 0.0,
    )

    private fun ops(scenario: ScenarioSpec, control: ControlSpec?) = OpsConfig(
        schemaVersion = CURRENT_OPS_SCHEMA_VERSION,
        control = control,
        scenarios = listOf(scenario),
    )

    private val control = ControlSpec(kafkaBootstrap = "kafka:9092", topic = "datagen-control")

    @Test
    fun `accepts external_signal when ops declares a control block`() {
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("s", UntilStopDurationSpec(), listOf(ExternalSignalStopSpec())), control),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `rejects external_signal with no control block, naming the scenario and the fix`() {
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("run-until-stopped", UntilStopDurationSpec(), listOf(ExternalSignalStopSpec())), null),
        )

        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0])
            .contains("scenario 'run-until-stopped'")
            .contains("external_signal")
            .contains("control:")
            .contains("kafka_bootstrap")
            .contains("topic")
    }

    @Test
    fun `rejects external_signal on a timed scenario too when no control block exists`() {
        // The condition is deliverable-or-not regardless of the duration kind; a timed scenario
        // declaring it is still declaring something nothing can raise.
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("timed", TimeDurationSpec("PT10M"), listOf(ExternalSignalStopSpec())), null),
        )

        assertThat(result.errors).hasSize(1)
    }

    @Test
    fun `leaves a scenario with no external_signal alone`() {
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("s", TimeDurationSpec("PT10M"), listOf(ErrorRateStopSpec(0.05))), null),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `warns, without failing, on until_stop_condition with no stop conditions`() {
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("nothing-ends-it", UntilStopDurationSpec(), emptyList()), null),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings[0])
            .contains("scenario 'nothing-ends-it'")
            .contains("until_stop_condition")
            .contains("external_signal")
    }

    @Test
    fun `does not warn about until_stop_condition that has a stop condition`() {
        val result = ExternalSignalControlValidator().validate(
            data(),
            ops(scenario("s", UntilStopDurationSpec(), listOf(ErrorRateStopSpec(0.05))), null),
        )

        assertThat(result.warnings).isEmpty()
    }
}
