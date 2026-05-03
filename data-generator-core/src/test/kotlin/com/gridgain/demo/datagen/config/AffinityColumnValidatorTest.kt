package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AffinityColumnValidatorTest {
    private val validator = AffinityColumnValidator()
    private val emptyOps = OpsConfig(schemaVersion = 2, scenarios = emptyList())
    private fun col(name: String, affinity: Boolean = false, key: Boolean = false) =
        ColumnSpec(name = name, nullRate = 0.0, affinity = affinity, key = key,
                   valueSource = SequenceSpec(start = 1, step = 1))

    @Test fun `passes with no affinity column`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0, listOf(col("id", key = true))))), emptyOps)
        assertThat(r.errors).isEmpty()
    }
    @Test fun `passes with exactly one affinity column`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0,
            listOf(col("id", key = true, affinity = true), col("name"))))), emptyOps)
        assertThat(r.errors).isEmpty()
    }
    @Test fun `fails with multiple affinity columns`() {
        val r = validator.validate(DataConfig(2, listOf(SchemaSpec("c", 0.0,
            listOf(col("id", key = true, affinity = true), col("name", affinity = true))))), emptyOps)
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("schema 'c'")
            .contains("more than one affinity column")
            .contains("id, name")
            .contains("Mark exactly one column with 'affinity: true'")
    }
}
