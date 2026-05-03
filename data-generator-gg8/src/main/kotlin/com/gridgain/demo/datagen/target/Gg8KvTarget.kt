package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.client.gg8.DemoAddressFinder
import org.apache.ignite.Ignition
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration

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

    private fun ensureClient(): IgniteClient {
        val existing = client
        if (existing != null) return existing
        synchronized(this) {
            val again = client
            if (again != null) return again
            val cfg = ClientConfiguration().setAddressesFinder(DemoAddressFinder(clusterName))
            val opened = try {
                Ignition.startClient(cfg)
            } catch (e: Exception) {
                throw MisconfigurationException(
                    "Gg8KvTarget could not connect to GG8 cluster '$clusterName': ${e.message}. " +
                    "Verify the cluster is reachable, client-endpoints.yaml is on the resolution path, " +
                    "and the cluster name matches the clusters[].name entry.",
                    cause = e,
                )
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
                    WriteOutcome(success = true)
                } catch (e: Exception) {
                    try { tx.rollback() } catch (_: Exception) { /* swallow rollback failure */ }
                    WriteOutcome(success = false, error = e)
                }
            } else {
                putAllForEvent(ignite, event)
                WriteOutcome(success = true)
            }
        } catch (e: Exception) {
            WriteOutcome(success = false, error = e)
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
}
