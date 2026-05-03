package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class NullRateOnRelationColumnValidatorTest {

    private fun col(name: String, vs: ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = name, nullRate = nullRate, valueSource = vs)

    @Test
    fun `accepts null_rate zero on a parent-fk-ref column`() {
        val s = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("c", "id", listOf(CohortBucket(1.0, 1))), nullRate = 0.0))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(s))
        assertThat(NullRateOnRelationColumnValidator().validate(data, OpsConfig(schemaVersion = 2, scenarios = emptyList())).errors).isEmpty()
    }

    @Test
    fun `rejects positive null_rate on a parent-fk-ref column`() {
        val s = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("c", "id", listOf(CohortBucket(1.0, 1))), nullRate = 0.05))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(s))
        val r = NullRateOnRelationColumnValidator().validate(data, OpsConfig(schemaVersion = 2, scenarios = emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("order.customer_id").contains("null_rate")
    }
}
