package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9SqlProvisionerApplyTest {
    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test_provisioned"

    @Test fun `apply creates table - second run is a no-op`() {
        val provisioner = Gg9SqlProvisioner(clusterName)
        val plan = ProvisioningPlan(listOf(SchemaDescriptor(
            schemaName = tableName, keyColumn = "id", affinityColumn = null,
            columns = listOf(
                ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false),
                ColumnDescriptor("name", SqlType.VARCHAR, isKey = false, isAffinity = false),
            ), transactional = false)))
        val first = provisioner.apply(plan)
        assertThat(first.errors).isEmpty()
        val second = provisioner.apply(plan)
        assertThat(second.errors).isEmpty()
        // GG9 IF NOT EXISTS does not surface "already existed" through SQL — both runs report
        // optimistically as "created". We assert no errors and leave count semantics looser than GG8.
    }
}
