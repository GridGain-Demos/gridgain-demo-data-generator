package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class KeyColumnValidatorTest {

    private fun col(name: String, isKey: Boolean = false) = ColumnSpec(
        name = name, nullRate = 0.0, key = isKey,
        valueSource = SequenceSpec(1, 1),
    )

    @Test
    fun `accepts a schema with exactly one key column`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("id", isKey = true), col("name")))))
        assertThat(KeyColumnValidator().validate(data, OpsConfig(2, emptyList())).errors).isEmpty()
    }

    @Test
    fun `rejects a schema with no key column`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("id"), col("name")))))
        val r = KeyColumnValidator().validate(data, OpsConfig(2, emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("no key column")
    }

    @Test
    fun `rejects a schema with multiple key columns`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(col("a", isKey = true), col("b", isKey = true)))))
        val r = KeyColumnValidator().validate(data, OpsConfig(2, emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer").contains("more than one key column")
    }
}
