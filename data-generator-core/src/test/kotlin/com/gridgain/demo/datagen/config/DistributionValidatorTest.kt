package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class DistributionValidatorTest {

    private fun data() = DataConfig(
        CURRENT_DATA_SCHEMA_VERSION,
        listOf(SchemaSpec("customer", 0.0,
            listOf(ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))))),
    )
    private fun gg8(name: String) = Gg8KvTargetSpec(name = name, clusterName = "trip")

    private fun scenario(name: String, distribution: DistributionSpec?) = ScenarioSpec(
        name = name, target = "gg8-trip", rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0), duration = TimeDurationSpec("PT1S"),
        readRatio = 0.0,
        distribution = distribution,
    )

    private fun ops(distribution: DistributionSpec?) = OpsConfig(
        schemaVersion = CURRENT_OPS_SCHEMA_VERSION,
        targets = listOf(gg8("gg8-trip")),
        scenarios = listOf(scenario("s", distribution)),
    )

    @Test
    fun `accepts a scenario without a distribution block`() {
        assertThat(DistributionValidator().validate(data(), ops(null)).errors).isEmpty()
    }

    @Test
    fun `accepts partition_count equal to replicas`() {
        val r = DistributionValidator().validate(data(), ops(DistributionSpec(replicas = 4, partitionCount = 4)))
        assertThat(r.errors).isEmpty()
    }

    @Test
    fun `accepts partition_count greater than replicas`() {
        val r = DistributionValidator().validate(data(), ops(DistributionSpec(replicas = 4, partitionCount = 16)))
        assertThat(r.errors).isEmpty()
    }

    @Test
    fun `rejects partition_count less than replicas`() {
        val r = DistributionValidator().validate(data(), ops(DistributionSpec(replicas = 4, partitionCount = 2)))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0])
            .contains("scenario 's'")
            .contains("partition_count")
            .contains("replicas")
            .contains("4")
            .contains("2")
    }
}
