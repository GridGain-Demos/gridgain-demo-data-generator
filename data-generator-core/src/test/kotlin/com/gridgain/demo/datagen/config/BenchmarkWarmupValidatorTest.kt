package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class BenchmarkWarmupValidatorTest {

    private fun data() = DataConfig(
        CURRENT_DATA_SCHEMA_VERSION,
        listOf(SchemaSpec("customer", 0.0,
            listOf(ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))))),
    )

    private fun scenario(name: String, duration: DurationSpec, warmup: WarmupSpec) = ScenarioSpec(
        concurrency = 1,
        name = name, rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0), duration = duration,
        stopConditions = emptyList(),
        operations = OperationMix(put = 0.5, get = 0.5, putGet = 0.0),
        warmup = warmup, keySpace = UnboundedKeySpaceSpec(),
    )

    private fun ops(scenario: ScenarioSpec) = OpsConfig(
        schemaVersion = CURRENT_OPS_SCHEMA_VERSION,
        control = null,
        scenarios = listOf(scenario),
    )

    @Test
    fun `warns, without failing, when an until_stop_condition scenario has no warmup`() {
        val result = BenchmarkWarmupValidator().validate(
            data(), ops(scenario("power-sat-192", UntilStopDurationSpec(), NoWarmupSpec())),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single())
            .contains("power-sat-192")
            .contains("warmup: {kind: none}")
            .contains("latency_*")
            .contains("warmup_applied")
    }

    @Test
    fun `warns when a timed scenario has no warmup`() {
        val result = BenchmarkWarmupValidator().validate(
            data(), ops(scenario("timed", TimeDurationSpec("PT10M"), NoWarmupSpec())),
        )

        assertThat(result.warnings).hasSize(1)
    }

    @Test
    fun `leaves a count-duration loader alone, which is where no warmup is correct`() {
        val result = BenchmarkWarmupValidator().validate(
            data(), ops(scenario("power-fill", CountDurationSpec(10_000_000), NoWarmupSpec())),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `says nothing when a measurement scenario declares a time warmup`() {
        val result = BenchmarkWarmupValidator().validate(
            data(), ops(scenario("s", UntilStopDurationSpec(), TimeWarmupSpec("PT90S"))),
        )

        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `says nothing when a measurement scenario declares a count warmup`() {
        val result = BenchmarkWarmupValidator().validate(
            data(), ops(scenario("s", TimeDurationSpec("PT10M"), CountWarmupSpec(1_000))),
        )

        assertThat(result.warnings).isEmpty()
    }
}
