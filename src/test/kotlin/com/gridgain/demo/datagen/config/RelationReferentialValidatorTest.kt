package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class RelationReferentialValidatorTest {

    private fun col(name: String, vs: ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = name, nullRate = nullRate, valueSource = vs)

    private val customer = SchemaSpec(
        name = "customer", updateRatio = 0.0,
        columns = listOf(col("id", SequenceSpec(1, 1)))
    )

    @Test
    fun `accepts a relation that resolves`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        assertThat(RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1)).errors).isEmpty()
    }

    @Test
    fun `rejects unknown parent schema`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("missing", "id", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("order.customer_id").contains("missing")
    }

    @Test
    fun `rejects unknown parent column`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", ParentFkRefSpec("customer", "absent", listOf(CohortBucket(1.0, 1)))))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = RelationReferentialValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("customer.absent")
    }
}
