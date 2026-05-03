package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.tx.Transaction
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
        val ddl = renderer.render(plan)
        val statements = ddl.split(";\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val errors = mutableListOf<String>()
        val created = mutableListOf<String>()

        val client: IgniteClient = try {
            IgniteClient.builder().addressFinder(DemoAddressFinder(clusterName)).build()
        } catch (e: Exception) {
            return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg9SqlProvisioner.apply could not connect to GG9 cluster '$clusterName': ${e.message}. " +
                "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
                "and the cluster name matches the clusters[].name entry."
            ))
        }
        client.use { ignite ->
            val sql = ignite.sql()
            for (stmt in statements) {
                try {
                    sql.execute(null as Transaction?, stmt).close()
                } catch (e: Exception) {
                    errors.add(
                        "Gg9SqlProvisioner.apply failed executing DDL [$stmt]: ${e.message}. " +
                        "If the table or zone already exists with a different definition, drop it manually " +
                        "and retry, or align data.yaml. IF NOT EXISTS guards prevent duplicate-create errors " +
                        "but cannot reconcile mismatched columns."
                    )
                }
            }
            if (errors.isEmpty()) created.addAll(plan.descriptors.map { it.schemaName })
        }
        return ProvisioningOutcome(emptyList(), created, emptyList(), errors)
    }
}
