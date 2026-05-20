package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.target.TransactionOutcome
import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.table.Tuple
import org.apache.ignite.tx.Transaction
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * GG9 KV target. Lazily opens an `IgniteClient` on first `write` or `read` call.
 * Closes the client on `close()`.
 *
 * @param clusterName must match a `clusters[].name` entry in the resolved client-endpoints.yaml
 * @param keyColumnByName maps each schema name to the name of its key column. The runner
 *     constructs this map from the parsed `DataConfig`.
 * @param transactionScope controls whether `write()` wraps the parent + child puts in a single
 *     GG9 transaction. Defaults to `NONE`. Set to `BUSINESS_EVENT` to opt in.
 */
class Gg9KvTarget(
    private val clusterName: String,
    private val keyColumnByName: Map<String, String>,
    private val transactionScope: TransactionScope = TransactionScope.NONE,
) : Target, AutoCloseable {

    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = true

    @Volatile private var client: IgniteClient? = null

    /** Cached fatal connection failure — see Gg8KvTarget for the rationale (avoids
     *  re-probing on every write when the cluster is unreachable). */
    @Volatile private var fatalConnectFailure: MisconfigurationException? = null

    /** Per-exception-class WARN-log de-dupe: log the first occurrence in full, then
     *  count repeats. Without this the per-op error path is opaque to anyone running
     *  the generator without an OTel exporter (the metric counter tags exception
     *  class but nothing surfaces it to stderr/stdout). */
    private val warnedClasses: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    private fun ensureClient(): IgniteClient {
        val existing = client
        if (existing != null) return existing
        fatalConnectFailure?.let { throw it }
        synchronized(this) {
            val again = client
            if (again != null) return again
            fatalConnectFailure?.let { throw it }
            // Pre-probe TCP reachability — see Gg8KvTarget for the rationale. The GG9
            // builder has connectTimeout, but we keep the manual probe symmetric with GG8
            // so the failure mode + error message are the same regardless of flavor.
            val finder = DemoAddressFinder(clusterName)
            try {
                probeReachability(finder.addresses, clusterName)
            } catch (e: MisconfigurationException) {
                fatalConnectFailure = e
                throw e
            }
            val opened = try {
                IgniteClient.builder()
                    .addressFinder(finder)
                    .connectTimeout(CONNECT_TIMEOUT_MS)
                    .build()
            } catch (e: Exception) {
                val wrapped = MisconfigurationException(
                    "Gg9KvTarget could not connect to GG9 cluster '$clusterName': ${e.message}. " +
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
        val ignite = try {
            ensureClient()
        } catch (e: Exception) {
            warnOnce(e, "connect to cluster '$clusterName'")
            // Pre-tx failure — never started a transaction, so NONE.
            return WriteOutcome(success = false, error = e, transactionOutcome = TransactionOutcome.NONE)
        }
        return if (transactionScope == TransactionScope.BUSINESS_EVENT) {
            try {
                ignite.transactions().runInTransaction<Unit> { tx ->
                    putAllForEvent(ignite, tx, event)
                }
                WriteOutcome(success = true, transactionOutcome = TransactionOutcome.COMMITTED)
            } catch (e: Exception) {
                warnOnce(e, "transactional write for schema '${event.parentSchemaName}'")
                // GG9's runInTransaction auto-rolls-back when its lambda throws; the
                // exception propagates here. Tag as ROLLED_BACK so the runner emits the
                // tx_rollback metric.
                WriteOutcome(success = false, error = e, transactionOutcome = TransactionOutcome.ROLLED_BACK)
            }
        } else {
            try {
                putAllForEvent(ignite, tx = null, event = event)
                WriteOutcome(success = true, transactionOutcome = TransactionOutcome.NONE)
            } catch (e: Exception) {
                warnOnce(e, "write for schema '${event.parentSchemaName}'")
                WriteOutcome(success = false, error = e, transactionOutcome = TransactionOutcome.NONE)
            }
        }
    }

    private fun warnOnce(e: Throwable, context: String) {
        val key = e.javaClass.name
        val count = warnedClasses.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        if (count == 1L) {
            // First sighting of this exception class — log full message + stack so the
            // user can root-cause. Subsequent occurrences are counted but not re-logged.
            LOG.warn("Gg9KvTarget $context failed (${e.javaClass.simpleName}): ${e.message}", e)
        }
    }

    private fun putAllForEvent(ignite: IgniteClient, tx: Transaction?, event: BusinessEvent) {
        val parentKeyColumn = keyColumnByName[event.parentSchemaName]
            ?: throw IllegalStateException(
                "no key column registered for parent schema '${event.parentSchemaName}'; " +
                "registered: ${keyColumnByName.keys}"
            )
        putRow(ignite, tx, event.parentSchemaName, parentKeyColumn, event.parentRow)
        event.childrenBySchema.forEach { (childSchema, rows) ->
            val childKeyColumn = keyColumnByName[childSchema]
                ?: throw IllegalStateException("no key column registered for schema '$childSchema'")
            rows.forEach { row -> putRow(ignite, tx, childSchema, childKeyColumn, row) }
        }
    }

    private fun putRow(
        ignite: IgniteClient,
        tx: Transaction?,
        schemaName: String,
        keyColumn: String,
        row: Map<String, Any?>,
    ) {
        val keyValue = row[keyColumn] ?: throw IllegalStateException(
            "row of schema '$schemaName' has null value in key column '$keyColumn'."
        )
        val table = ignite.tables().table(schemaName) ?: throw IllegalStateException(
            "GG9 table '$schemaName' does not exist in the cluster. " +
            "Pre-create the table (apply the SQL emitted to /<demoOutputDirectory>/data-generator/provisioning/) " +
            "or change ops.yaml's scenario provisioning to 'apply' so the generator creates it on startup."
        )
        val keyTuple = Tuple.create().set(keyColumn, keyValue)
        val valueTuple = Tuple.create()
        for ((col, v) in row) {
            if (col == keyColumn) continue
            valueTuple.set(col, v)
        }
        table.keyValueView().put(tx, keyTuple, valueTuple)
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        return try {
            val ignite = ensureClient()
            val table = ignite.tables().table(cacheName) ?: throw IllegalStateException(
                "GG9 table '$cacheName' does not exist in the cluster."
            )
            val keyColumn = keyColumnByName[cacheName]
                ?: throw IllegalStateException(
                    "no key column registered for schema '$cacheName'; " +
                    "registered: ${keyColumnByName.keys}"
                )
            val keyTuple = Tuple.create().set(keyColumn, key)
            val value: Tuple? = table.keyValueView().get(null, keyTuple)
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
     * `MisconfigurationException` with remediation if every probe fails. Symmetric with
     * `Gg8KvTarget.probeReachability`.
     *
     * `*.svc.cluster.local` addresses are filtered ONLY when running outside Kubernetes —
     * `DemoAddressFinder` returns both `local` and `in_cluster` contexts, and the
     * cluster.local ones would always time out from a developer laptop. When the data
     * generator runs as a Job inside the cluster (the plugin's in-cluster mode), the
     * cluster.local addresses are the only routable ones, so we keep them.
     */
    private fun probeReachability(addresses: Array<String>, clusterName: String) {
        val inCluster = System.getenv("KUBERNETES_SERVICE_HOST") != null
        val routable = if (inCluster) addresses.toList()
        else addresses.filter { !it.contains(".svc.cluster.local") }
        if (routable.isEmpty()) {
            throw MisconfigurationException(
                "Gg9KvTarget: DemoAddressFinder returned no routable addresses for cluster '$clusterName' " +
                "(received: ${addresses.joinToString(", ").ifBlank { "(none)" }}; in-cluster=$inCluster). " +
                "Verify client-endpoints.yaml has a clusters[].name entry matching '$clusterName' " +
                "and that the appropriate context's addresses are populated."
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
            "Gg9KvTarget could not reach any endpoint of GG9 cluster '$clusterName' within " +
            "${PROBE_TIMEOUT_MS}ms per address. Failures:\n" +
            failures.joinToString(separator = "\n  - ", prefix = "  - ") + "\n" +
            "Verify the cluster is up, network paths are open, and client-endpoints.yaml " +
            "addresses match the running cluster."
        )
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(Gg9KvTarget::class.java)

        /** Per-address TCP probe timeout (3s — matches GG8 path). */
        const val PROBE_TIMEOUT_MS: Int = 3_000

        /** Passed to GG9's IgniteClient.builder.connectTimeout — bounds the GG9 client's own
         *  internal connect after the pre-probe succeeds. */
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
    }
}
