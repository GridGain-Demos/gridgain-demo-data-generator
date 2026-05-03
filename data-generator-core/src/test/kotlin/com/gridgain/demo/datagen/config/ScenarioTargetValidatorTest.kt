package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ScenarioTargetValidatorTest {

    private fun col() = ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
    private fun data() = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
    private fun gg8(name: String) = Gg8KvTargetSpec(name = name, clusterName = "trip")

    private fun scenario(name: String, target: String, readRatio: Double = 0.0,
                         tx: TransactionScope = TransactionScope.NONE) = ScenarioSpec(
        name = name, target = target, rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0), duration = TimeDurationSpec("PT1S"),
        transactionScope = tx, readRatio = readRatio,
    )

    @Test
    fun `accepts a scenario referencing a declared target`() {
        val ops = OpsConfig(schemaVersion = 2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", "gg8-trip")))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `rejects a scenario with empty target`() {
        val ops = OpsConfig(schemaVersion = 2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", target = "")))
        val r = ScenarioTargetValidator().validate(data(), ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("scenario 's'").contains("target")
    }

    @Test
    fun `rejects a scenario referencing an undeclared target`() {
        val ops = OpsConfig(schemaVersion = 2, targets = listOf(gg8("gg8-trip")), scenarios = listOf(scenario("s", "missing")))
        val r = ScenarioTargetValidator().validate(data(), ops)
        assertThat(r.errors[0]).contains("missing").contains("not declared")
    }

    @Test
    fun `Gg8 target supports reads — read_ratio gt 0 accepted`() {
        val ops = OpsConfig(schemaVersion = 2, targets = listOf(gg8("gg8-trip")),
            scenarios = listOf(scenario("s", "gg8-trip", readRatio = 0.5)))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }

    @Test
    fun `Gg8 target supports transactions — business_event accepted`() {
        val ops = OpsConfig(schemaVersion = 2, targets = listOf(gg8("gg8-trip")),
            scenarios = listOf(scenario("s", "gg8-trip", tx = TransactionScope.BUSINESS_EVENT)))
        assertThat(ScenarioTargetValidator().validate(data(), ops).errors).isEmpty()
    }
}
