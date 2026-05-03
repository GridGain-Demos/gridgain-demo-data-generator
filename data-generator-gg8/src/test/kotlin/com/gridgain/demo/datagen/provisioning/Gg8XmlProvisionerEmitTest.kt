package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class Gg8XmlProvisionerEmitTest {
    private val provisioner = Gg8XmlProvisioner(clusterName = "unused-for-emit")

    @Test fun `emit writes one xml file per schema`(@TempDir dest: Path) {
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
        assertThat(outcome.artifactsWritten).containsExactly(dest.resolve("customer.xml"), dest.resolve("order.xml"))
        assertThat(Files.readString(dest.resolve("customer.xml")))
            .contains("value=\"customer\"").contains("ATOMIC").doesNotContain("keyConfiguration")
        assertThat(Files.readString(dest.resolve("order.xml")))
            .contains("value=\"order\"").contains("TRANSACTIONAL").contains("customer_id")
    }
}
