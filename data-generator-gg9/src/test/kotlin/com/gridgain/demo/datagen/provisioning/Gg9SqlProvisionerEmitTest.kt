package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class Gg9SqlProvisionerEmitTest {
    private val provisioner = Gg9SqlProvisioner(clusterName = "unused-for-emit")

    @Test fun `emit writes one ddl_sql file`(@TempDir dest: Path) {
        val plan = ProvisioningPlan(listOf(
            SchemaDescriptor("customer", "id", null,
                listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false),
            SchemaDescriptor("order", "id", "customer_id", listOf(
                ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false),
            ), true),
        ))
        val outcome = provisioner.emit(plan, dest)
        assertThat(outcome.errors).isEmpty()
        assertThat(outcome.artifactsWritten).containsExactly(dest.resolve("ddl.sql"))
        val ddl = Files.readString(dest.resolve("ddl.sql"))
        assertThat(ddl)
            .startsWith("CREATE ZONE IF NOT EXISTS gg_demo_zone")
            .contains("CREATE TABLE IF NOT EXISTS customer")
            .contains("CREATE TABLE IF NOT EXISTS order")
            .contains("COLOCATE BY (customer_id)")
    }
}
