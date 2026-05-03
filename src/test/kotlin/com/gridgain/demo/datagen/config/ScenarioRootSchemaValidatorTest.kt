package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ScenarioRootSchemaValidatorTest {

    private fun col() = ColumnSpec("id", 0.0, SequenceSpec(1, 1))
    private fun scenario(name: String, roots: List<String>) = ScenarioSpec(
        name = name, rootSchemas = roots,
        rate = ConstantRateSpec(100.0),
        duration = TimeDurationSpec("PT10S"),
        transactionScope = TransactionScope.BUSINESS_EVENT,
        readRatio = 0.0,
    )

    @Test
    fun `accepts a scenario whose root schema exists`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(scenario("s", listOf("customer"))))
        assertThat(ScenarioRootSchemaValidator().validate(data, ops).errors).isEmpty()
    }

    @Test
    fun `rejects a scenario whose root schema is unknown`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(scenario("s", listOf("missing"))))
        val r = ScenarioRootSchemaValidator().validate(data, ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("s").contains("missing")
    }

    @Test
    fun `rejects duplicate scenario names`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col()))))
        val ops = OpsConfig(2, listOf(
            scenario("s", listOf("customer")),
            scenario("s", listOf("customer")),
        ))
        val r = ScenarioRootSchemaValidator().validate(data, ops)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("duplicate scenario")
    }
}
