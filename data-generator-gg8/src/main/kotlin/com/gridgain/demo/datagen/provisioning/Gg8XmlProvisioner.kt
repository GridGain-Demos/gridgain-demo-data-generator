package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.client.gg8.DemoAddressFinder
import org.apache.ignite.Ignition
import org.apache.ignite.cache.CacheAtomicityMode
import org.apache.ignite.cache.CacheKeyConfiguration
import org.apache.ignite.client.ClientCacheConfiguration
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration
import java.net.InetSocketAddress
import java.net.Socket
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
        val finder = DemoAddressFinder(clusterName)
        // Pre-probe TCP reachability — see Gg8KvTarget for the rationale.
        probeReachability(finder.addresses, clusterName)?.let { return it }
        val cfg = ClientConfiguration()
            .setAddressesFinder(finder)
            .setTimeout(10_000)
        val created = mutableListOf<String>()
        val existed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val client: IgniteClient = try {
            Ignition.startClient(cfg)
        } catch (e: Exception) {
            return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg8XmlProvisioner.apply could not connect to GG8 cluster '$clusterName': ${e.message}. " +
                "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
                "and the cluster name matches the clusters[].name entry."
            ))
        }
        client.use { ignite ->
            val existing: Set<String> = ignite.cacheNames().toSet()
            for (d in plan.descriptors) {
                val alreadyExists = d.schemaName in existing
                try {
                    val cacheCfg = ClientCacheConfiguration().apply {
                        setName(d.schemaName)
                        setAtomicityMode(if (d.transactional) CacheAtomicityMode.TRANSACTIONAL else CacheAtomicityMode.ATOMIC)
                        d.affinityColumn?.let { setKeyConfiguration(CacheKeyConfiguration("java.lang.Object", it)) }
                    }
                    ignite.getOrCreateCache<Any, Any>(cacheCfg)
                    if (alreadyExists) existed.add(d.schemaName) else created.add(d.schemaName)
                } catch (e: Exception) {
                    errors.add(
                        "Gg8XmlProvisioner.apply failed for cache '${d.schemaName}': ${e.message}. " +
                        "If the cache already exists with a different config (atomicityMode, affinityKey, or " +
                        "backups), GG8's getOrCreateCache rejects the call. Either tear down the cache and re-run, " +
                        "or align data.yaml to the existing cache's config."
                    )
                }
            }
        }
        return ProvisioningOutcome(emptyList(), created, existed, errors)
    }

    /**
     * Pre-probe TCP reachability. Returns a `ProvisioningOutcome` populated with a single
     * error string if every address fails the 10s probe, or `null` to indicate that
     * `Ignition.startClient` may proceed.
     */
    private fun probeReachability(addresses: Array<String>, clusterName: String): ProvisioningOutcome? {
        val inCluster = System.getenv("KUBERNETES_SERVICE_HOST") != null
        val routable = if (inCluster) addresses.toList()
        else addresses.filter { !it.contains(".svc.cluster.local") }
        if (routable.isEmpty()) {
            return ProvisioningOutcome(emptyList(), emptyList(), emptyList(), listOf(
                "Gg8XmlProvisioner: DemoAddressFinder returned no routable addresses for cluster '$clusterName' " +
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
            "Gg8XmlProvisioner could not reach any endpoint of GG8 cluster '$clusterName' within " +
            "3000ms per address. Failures:\n" +
            failures.joinToString(separator = "\n  - ", prefix = "  - ") + "\n" +
            "Verify the cluster is up, network paths are open, and client-endpoints.yaml " +
            "addresses match the running cluster."
        ))
    }
}
