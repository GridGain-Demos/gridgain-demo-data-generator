package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ColumnUniquenessValidatorTest {

    private fun col(name: String) = ColumnSpec(
        name = name, nullRate = 0.0, valueSource = SequenceSpec(start = 1, step = 1)
    )

    @Test
    fun `accepts unique column names within unique schemas`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"), col("first_name"))),
                SchemaSpec("order", 0.0, listOf(col("id"), col("amount"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).isEmpty()
    }

    @Test
    fun `rejects duplicate column names within a schema`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"), col("id"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("customer")
            .contains("id")
            .contains("duplicate column")
    }

    @Test
    fun `rejects duplicate schema names`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(
                SchemaSpec("customer", 0.0, listOf(col("id"))),
                SchemaSpec("customer", 0.0, listOf(col("ref"))),
            ),
        )
        val r = ColumnUniquenessValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("customer")
            .contains("duplicate schema")
    }
}
