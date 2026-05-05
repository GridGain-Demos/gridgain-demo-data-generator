package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.observability.LifecycleEvent
import com.gridgain.demo.datagen.provisioning.ColumnDescriptor
import com.gridgain.demo.datagen.provisioning.Gg8XmlProvisioner
import com.gridgain.demo.datagen.provisioning.ProvisioningPlan
import com.gridgain.demo.datagen.provisioning.SchemaDescriptor
import com.gridgain.demo.datagen.provisioning.SqlType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * Validates the data flow Gg8Main uses post-provisioning: run Gg8XmlProvisioner.emit and
 * confirm that mapping its outcome onto LifecycleEvent.ProvisioningApplied produces the
 * spec'd attribute shape (flavor=gg8, mode=emit, artifacts/created/existed counts).
 *
 * We don't invoke `main()` directly (it calls exitProcess); the integration is verified at
 * smoke time (Plan 11 Task 10).
 */
class Gg8MainProvisioningEventTest {

    @Test fun `provisioner emit outcome maps to ProvisioningApplied with flavor gg8 and mode emit`(
        @TempDir dest: Path,
    ) {
        val provisioner = Gg8XmlProvisioner(clusterName = "unused-for-emit")
        val plan = ProvisioningPlan(listOf(
            SchemaDescriptor("customer", "id", null, listOf(
                ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false),
            ), false),
        ))
        val outcome = provisioner.emit(plan, dest)
        assertThat(outcome.errors).isEmpty()
        assertThat(outcome.artifactsWritten).hasSize(1)

        val event = LifecycleEvent.ProvisioningApplied(
            flavor = "gg8",
            mode = "emit",
            artifactsWritten = outcome.artifactsWritten.size,
            createdCount = outcome.cachesOrTablesCreated.size,
            existedCount = outcome.cachesOrTablesAlreadyExisted.size,
        )
        assertThat(event.name()).isEqualTo("provisioning.applied")
        assertThat(event.toAttributes())
            .containsEntry("flavor", "gg8")
            .containsEntry("mode", "emit")
            .containsEntry("artifacts_written", "1")
            .containsEntry("created_count", "0")
            .containsEntry("existed_count", "0")
    }
}
