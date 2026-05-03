package com.gridgain.demo.datagen.provisioning

import java.nio.file.Files
import java.nio.file.Path

class Gg9SqlProvisioner(
    private val clusterName: String,
    private val renderer: Gg9SqlDdlRenderer = Gg9SqlDdlRenderer(),
) : Provisioner {

    override fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome {
        if (!Files.isDirectory(destinationDir)) Files.createDirectories(destinationDir)
        return try {
            val path = destinationDir.resolve("ddl.sql")
            Files.writeString(path, renderer.render(plan))
            ProvisioningOutcome(listOf(path), emptyList(), emptyList(), emptyList())
        } catch (e: Exception) {
            ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg9SqlProvisioner.emit failed writing ${destinationDir.resolve("ddl.sql")}: ${e.message}. " +
                "Verify the destination is writable."
            ))
        }
    }

    override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
        // Implemented in Task 12.
        throw NotImplementedError("Gg9SqlProvisioner.apply lands in Plan 9 Task 12")
    }
}
