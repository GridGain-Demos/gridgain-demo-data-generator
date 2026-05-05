package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.target.TransactionOutcome
import com.gridgain.demo.client.gg8.DemoAddressFinder
import org.apache.ignite.Ignition
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration
import java.net.InetSocketAddress
import java.net.Socket

/**
 * GG8 KV target. Lazily opens an `IgniteClient` on first `write` or `read` call.
 * Closes the client on `close()`.
 *
 * @param clusterName must match a `clusters[].name` entry in the resolved client-endpoints.yaml
 * @param keyColumnByName maps each schema name to the name of its key column. The runner
 *     constructs this map from the parsed `DataConfig`.
 * @param transactionScope controls whether `write()` wraps the parent + child puts in a single
 *     GG8 transaction. Defaults to `NONE`. Set to `BUSINESS_EVENT` to opt in. Note: GG8 8.9+
 *     rejects atomic-cache operations inside transactions, so `BUSINESS_EVENT` requires every
 *     target cache to be configured with `CacheAtomicityMode.TRANSACTIONAL`.
 */
class Gg8KvTarget(
    private val clusterName: String,
    private val keyColumnByName: Map<String, String>,
    private val transactionScope: TransactionScope = TransactionScope.NONE,
) : Target, AutoCloseable {

    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = true

    @Volatile private var client: IgniteClient? = null

    /** Cached fatal connection failure — once we've decided the cluster is unreachable, every
     *  subsequent ensureClient call rethrows immediately instead of reprobing. Without this,
     *  ScenarioRunner's write loop keeps invoking ensureClient (catching errors per-event),
     *  multiplying a 6s probe budget by N events. */
    @Volatile private var fatalConnectFailure: MisconfigurationException? = null

    private fun ensureClient(): IgniteClient {
        val existing = client
        if (existing != null) return existing
        fatalConnectFailure?.let { throw it }
        synchronized(this) {
            val again = client
            if (again != null) return again
            fatalConnectFailure?.let { throw it }
            // Pre-probe TCP reachability so an unreachable cluster fails fast instead of
            // hanging on the kernel's default TCP retry budget (~minutes). GG8's thin client
            // ClientConfiguration.setTimeout only bounds operations *after* connection — the
            // initial socket.connect() blocks indefinitely. setTimeout is still applied as a
            // defense against post-connect op hangs.
            val finder = DemoAddressFinder(clusterName)
            try {
                probeReachability(finder.addresses, clusterName)
            } catch (e: MisconfigurationException) {
                fatalConnectFailure = e
                throw e
            }
            val cfg = ClientConfiguration()
                .setAddressesFinder(finder)
                .setTimeout(CONNECT_TIMEOUT_MS)
            val opened = try {
                Ignition.startClient(cfg)
            } catch (e: Exception) {
                val wrapped = MisconfigurationException(
                    "Gg8KvTarget could not connect to GG8 cluster '$clusterName': ${e.message}. " +
                    "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
                    "and the cluster name matches the clusters[].name entry.",
                    cause = e,
                )
                fatalConnectFailure = wrapped
                throw wrapped
            }
            client = opened
            return opened
        }
    }

    override fun write(event: BusinessEvent): WriteOutcome {
        return try {
            val ignite = ensureClient()
            if (transactionScope == TransactionScope.BUSINESS_EVENT) {
                val tx = ignite.transactions().txStart()
                try {
                    putAllForEvent(ignite, event)
                    tx.commit()
                    WriteOutcome(success = true, transactionOutcome = TransactionOutcome.COMMITTED)
                } catch (e: Exception) {
                    try { tx.rollback() } catch (_: Exception) { /* swallow rollback failure */ }
                    WriteOutcome(success = false, error = e, transactionOutcome = TransactionOutcome.ROLLED_BACK)
                }
            } else {
                putAllForEvent(ignite, event)
                WriteOutcome(success = true, transactionOutcome = TransactionOutcome.NONE)
            }
        } catch (e: Exception) {
            // Pre-tx failure (e.g., ensureClient) — never started a transaction, so NONE.
            WriteOutcome(success = false, error = e, transactionOutcome = TransactionOutcome.NONE)
        }
    }

    private fun putAllForEvent(ignite: IgniteClient, event: BusinessEvent) {
        val parentKeyColumn = keyColumnByName[event.parentSchemaName]
            ?: throw IllegalStateException(
                "no key column registered for parent schema '${event.parentSchemaName}'; " +
                "registered: ${keyColumnByName.keys}"
            )
        putRow(ignite, event.parentSchemaName, parentKeyColumn, event.parentRow)
        event.childrenBySchema.forEach { (childSchema, rows) ->
            val childKeyColumn = keyColumnByName[childSchema]
                ?: throw IllegalStateException("no key column registered for schema '$childSchema'")
            rows.forEach { row -> putRow(ignite, childSchema, childKeyColumn, row) }
        }
    }

    private fun putRow(ignite: IgniteClient, schemaName: String, keyColumn: String, row: Map<String, Any?>) {
        val key = row[keyColumn] ?: throw IllegalStateException(
            "row of schema '$schemaName' has null value in key column '$keyColumn'."
        )
        val cache = ignite.getOrCreateCache<Any, Map<String, Any?>>(schemaName)
        cache.put(key, row)
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        return try {
            val ignite = ensureClient()
            val cache = ignite.getOrCreateCache<Any, Map<String, Any?>>(cacheName)
            val value = cache.get(key)
            ReadOutcome(success = true, value = value)
        } catch (e: Exception) {
            ReadOutcome(success = false, error = e)
        }
    }

    override fun close() {
        client?.close()
        client = null
    }

    /**
     * Probes each `host:port` with a manual `Socket.connect(addr, timeout)` and throws
     * `MisconfigurationException` with remediation if every probe fails. Lets the caller
     * fail fast on an unreachable cluster instead of waiting on the kernel's default TCP
     * retry budget (~minutes).
     *
     * Skips `*.svc.cluster.local` addresses — `DemoAddressFinder` returns both `local` and
     * `in_cluster` contexts; the cluster.local ones are only resolvable from inside the
     * target k8s cluster and would always time out from a developer laptop or CI runner.
     */
    private fun probeReachability(addresses: Array<String>, clusterName: String) {
        val routable = addresses.filter { !it.contains(".svc.cluster.local") }
        if (routable.isEmpty()) {
            throw MisconfigurationException(
                "Gg8KvTarget: DemoAddressFinder returned no routable addresses for cluster '$clusterName' " +
                "(received: ${addresses.joinToString(", ").ifBlank { "(none)" }}). " +
                "Verify client-endpoints.yaml has a clusters[].name entry matching '$clusterName' " +
                "and that the local context's addresses are populated."
            )
        }
        val failures = mutableListOf<String>()
        for (addr in routable) {
            val (host, port) = addr.substringBefore(':') to addr.substringAfter(':').toInt()
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
                return
            } catch (e: Exception) {
                failures.add("$addr: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw MisconfigurationException(
            "Gg8KvTarget could not reach any endpoint of GG8 cluster '$clusterName' within " +
            "${PROBE_TIMEOUT_MS}ms per address. Failures:\n" +
            failures.joinToString(separator = "\n  - ", prefix = "  - ") + "\n" +
            "Verify the cluster is up, network paths are open, and client-endpoints.yaml " +
            "addresses match the running cluster."
        )
    }

    private companion object {
        /** Per-address TCP probe timeout. 3s is generous for a healthy LAN/WAN endpoint and
         *  short enough that 4 addresses fail in <15s instead of the kernel's multi-minute retry. */
        const val PROBE_TIMEOUT_MS: Int = 3_000

        /** ClientConfiguration.setTimeout — bounds post-connect ops (not the initial socket
         *  connect, which the pre-probe handles). 10s is generous for any healthy cluster. */
        const val CONNECT_TIMEOUT_MS: Int = 10_000
    }
}
