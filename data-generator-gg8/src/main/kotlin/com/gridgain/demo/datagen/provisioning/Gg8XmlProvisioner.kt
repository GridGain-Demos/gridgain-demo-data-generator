package com.gridgain.demo.datagen.provisioning

import java.nio.file.Files
import java.nio.file.Path

class Gg8XmlProvisioner(
    private val clusterName: String,
    private val renderer: Gg8CacheXmlRenderer = Gg8CacheXmlRenderer(),
) : Provisioner {

    override fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome {
        if (!Files.isDirectory(destinationDir)) Files.createDirectories(destinationDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        for (d in plan.descriptors) {
            try {
                val path = destinationDir.resolve("${d.schemaName}.xml")
                Files.writeString(path, renderer.render(d))
                written.add(path)
            } catch (e: Exception) {
                errors.add(
                    "Gg8XmlProvisioner.emit failed for schema '${d.schemaName}' " +
                    "writing under $destinationDir: ${e.message}. " +
                    "Verify the destination is writable and the schema name is a valid filename."
                )
            }
        }
        return ProvisioningOutcome(written, emptyList(), emptyList(), errors)
    }

    override fun apply(plan: ProvisioningPlan): ProvisioningOutcome {
        // Implemented in Task 8.
        throw NotImplementedError("Gg8XmlProvisioner.apply lands in Plan 9 Task 8")
    }
}
