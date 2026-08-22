package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.*
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ProvisioningPlanFactoryTest {
    private fun col(name: String, vs: ValueSourceSpec, key: Boolean = false, affinity: Boolean = false) =
        ColumnSpec(name = name, nullRate = 0.0, affinity = affinity, key = key, valueSource = vs)
    private fun scenario(scope: TransactionScope = TransactionScope.NONE) =
        ScenarioSpec(name = "s1", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1.0), duration = CountDurationSpec(1L),
            transactionScope = scope, readRatio = 0.0)

    @Test fun `simple schema with sequence key`() {
        val data = DataConfig(2, listOf(SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1), key = true),
            col("name", DataFakerSpec("#{name.fullName}")),
        ))))
        val d = ProvisioningPlanFactory.from(data, scenario()).descriptors.single()
        assertThat(d.schemaName).isEqualTo("customer")
        assertThat(d.keyColumn).isEqualTo("id")
        assertThat(d.affinityColumn).isNull()
        assertThat(d.transactional).isFalse()
        assertThat(d.columns).extracting<String> { it.name }.containsExactly("id", "name")
        assertThat(d.columns).extracting<SqlType> { it.type }.containsExactly(SqlType.BIGINT, SqlType.VARCHAR)
    }

    @Test fun `transactional true when scenario uses business_event`() {
        val data = DataConfig(2, listOf(SchemaSpec("c", 0.0, listOf(col("id", SequenceSpec(1, 1), key = true)))))
        val plan = ProvisioningPlanFactory.from(data, scenario(scope = TransactionScope.BUSINESS_EVENT))
        assertThat(plan.descriptors.single().transactional).isTrue()
    }

    @Test fun `affinity column captured and parent-fk-ref inherits parent SqlType`() {
        val data = DataConfig(2, listOf(
            SchemaSpec("customer", 0.0, listOf(col("id", SequenceSpec(1, 1), key = true))),
            SchemaSpec("order", 0.0, listOf(
                col("customer_id", ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1))), affinity = true),
                col("id", KeySuffixSpec("customer_id", "-", 6), key = true),
            )),
        ))
        val d = ProvisioningPlanFactory.from(data, scenario()).descriptors.first { it.schemaName == "order" }
        assertThat(d.affinityColumn).isEqualTo("customer_id")
        assertThat(d.keyColumn).isEqualTo("id")
        assertThat(d.columns.first { it.name == "customer_id" }.type).isEqualTo(SqlType.BIGINT)  // inherited from customer.id
    }
}
