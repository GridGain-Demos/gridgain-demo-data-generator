package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MultiFkToSameParentValidatorTest {

    private fun col(name: String, vs: ValueSourceSpec) =
        ColumnSpec(name = name, nullRate = 0.0, valueSource = vs)

    private val customer = SchemaSpec(
        name = "customer", updateRatio = 0.0,
        columns = listOf(col("id", SequenceSpec(1, 1)))
    )

    private val supplier = SchemaSpec(
        name = "supplier", updateRatio = 0.0,
        columns = listOf(col("id", SequenceSpec(1, 1)))
    )

    private fun fk(parent: String) = ParentFkRefSpec(parent, "id", listOf(CohortBucket(1.0, 1)))

    @Test fun `single parent-fk-ref is fine`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(col("customer_id", fk("customer")))
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = MultiFkToSameParentValidator().validate(data, OpsConfig(schemaVersion = 2, scenarios = emptyList()))
        assertThat(r.errors).isEmpty()
    }

    @Test fun `two parent-fk-refs to different parents is fine`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(
                col("customer_id", fk("customer")),
                col("supplier_id", fk("supplier")),
            ),
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, supplier, order))
        val r = MultiFkToSameParentValidator().validate(data, OpsConfig(schemaVersion = 2, scenarios = emptyList()))
        assertThat(r.errors).isEmpty()
    }

    @Test fun `two parent-fk-refs to the same parent is rejected`() {
        val order = SchemaSpec(
            name = "order", updateRatio = 0.0,
            columns = listOf(
                col("customer_id", fk("customer")),
                col("billing_customer_id", fk("customer")),
            ),
        )
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val r = MultiFkToSameParentValidator().validate(data, OpsConfig(schemaVersion = 2, scenarios = emptyList()))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("order")
            .contains("customer_id")
            .contains("billing_customer_id")
            .contains("customer")
    }
}
