package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.tx.Transaction
import java.net.InetSocketAddress
import java.net.Socket
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

        val finder = DemoAddressFinder(clusterName)
        // Pre-probe TCP reachability — see Gg8XmlProvisioner for rationale.
        probeReachability(finder.addresses, clusterName)?.let { return it }

        val client: IgniteClient = try {
            IgniteClient.builder()
                .addressFinder(finder)
                .connectTimeout(10_000L)
                .build()
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

    /**
     * Pre-probe TCP reachability. Returns a `ProvisioningOutcome` populated with a single
     * error string if every address fails the 10s probe, or `null` to proceed.
     *
     * `*.svc.cluster.local` addresses are filtered ONLY when running outside Kubernetes —
     * when the data generator runs as a Job inside the cluster (the plugin's in-cluster
     * mode), the cluster.local addresses are the only routable ones.
     */
    private fun probeReachability(addresses: Array<String>, clusterName: String): ProvisioningOutcome? {
        val inCluster = System.getenv("KUBERNETES_SERVICE_HOST") != null
        val routable = if (inCluster) addresses.toList()
        else addresses.filter { !it.contains(".svc.cluster.local") }
        if (routable.isEmpty()) {
            return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg9SqlProvisioner: DemoAddressFinder returned no routable addresses for cluster '$clusterName' " +
                "(received: ${addresses.joinToString(", ").ifBlank { "(none)" }}; in-cluster=$inCluster). " +
                "Verify client-endpoints.yaml has a clusters[].name entry matching '$clusterName' " +
                "and that the appropriate context's addresses are populated."
            ))
        }
        val failures = mutableListOf<String>()
        for (addr in routable) {
            val (host, port) = addr.substringBefore(':') to addr.substringAfter(':').toInt()
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }
                return null
            } catch (e: Exception) {
                failures.add("$addr: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
            "Gg9SqlProvisioner could not reach any endpoint of GG9 cluster '$clusterName' within " +
            "3000ms per address. Failures:\n" +
            failures.joinToString(separator = "\n  - ", prefix = "  - ") + "\n" +
            "Verify the cluster is up, network paths are open, and client-endpoints.yaml " +
            "addresses match the running cluster."
        ))
    }
}
