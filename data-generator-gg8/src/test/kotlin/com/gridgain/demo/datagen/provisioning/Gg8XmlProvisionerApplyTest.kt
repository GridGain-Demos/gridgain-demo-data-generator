package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8XmlProvisionerApplyTest {
    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test_provisioned"

    @Test fun `apply creates a transactional cache, second run is a no-op`() {
        val provisioner = Gg8XmlProvisioner(clusterName)
        val plan = ProvisioningPlan(listOf(SchemaDescriptor(
            schemaName = cacheName, keyColumn = "id", affinityColumn = "id",
            columns = listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = true)),
            transactional = true)))
        val first = provisioner.apply(plan)
        assertThat(first.errors).isEmpty()
        val second = provisioner.apply(plan)
        assertThat(second.errors).isEmpty()
        assertThat(second.cachesOrTablesCreated).isEmpty()
        assertThat(second.cachesOrTablesAlreadyExisted).contains(cacheName)
    }
}
