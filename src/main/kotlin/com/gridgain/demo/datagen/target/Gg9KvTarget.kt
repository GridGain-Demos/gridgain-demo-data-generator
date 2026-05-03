package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.client.gg9.DemoAddressFinder
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.table.IgniteTables
import org.apache.ignite.table.Tuple
import org.apache.ignite.tx.IgniteTransactions
import org.apache.ignite.tx.Transaction

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

    private fun ensureClient(): IgniteClient {
        val existing = client
        if (existing != null) return existing
        synchronized(this) {
            val again = client
            if (again != null) return again
            val opened = try {
                IgniteClient.builder()
                    .addressFinder(DemoAddressFinder(clusterName))
                    .build()
            } catch (e: Exception) {
                throw MisconfigurationException(
                    "Gg9KvTarget could not connect to GG9 cluster '$clusterName': ${e.message}. " +
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
                gg9Transactions(ignite).runInTransaction { tx ->
                    putAllForEvent(ignite, tx, event)
                }
                WriteOutcome(success = true)
            } else {
                putAllForEvent(ignite, tx = null, event = event)
                WriteOutcome(success = true)
            }
        } catch (e: Exception) {
            WriteOutcome(success = false, error = e)
        }
    }

    private fun putAllForEvent(ignite: IgniteClient, tx: Transaction?, event: BusinessEvent) {
        val parentKeyColumn = keyColumnByName.values.firstOrNull { col -> event.parentRow.containsKey(col) }
            ?: throw IllegalStateException(
                "could not resolve parent schema's key column from event; " +
                "event.parentRow keys=${event.parentRow.keys}, registered key columns=${keyColumnByName.values}"
            )
        val parentSchemaName = keyColumnByName.entries.first { it.value == parentKeyColumn }.key
        putRow(ignite, tx, parentSchemaName, parentKeyColumn, event.parentRow)
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
        val table = gg9Tables(ignite).table(schemaName) ?: throw IllegalStateException(
            "GG9 table '$schemaName' does not exist in the cluster. " +
            "Pre-create the table or run with provisioning (Plan 9, deferred)."
        )
        val keyTuple = Tuple.create().set(keyColumn, keyValue)
        val valueTuple = Tuple.create()
        for ((col, v) in row) {
            if (col == keyColumn) continue
            valueTuple.set(col, v)
        }
        table.keyValueView().put(tx, keyTuple, valueTuple)
    }

    /**
     * Obtains the GG9 [IgniteTables] from the client via its concrete runtime type.
     *
     * Both `org.apache.ignite.client.IgniteClient` (GG8) and `org.apache.ignite.client.IgniteClient`
     * (GG9) live on the compile classpath, and the Kotlin compiler resolves GG8's version when
     * resolving the superinterface chain of GG9's `IgniteClient` (because `ignite-core` precedes
     * `ignite-api` on the classpath). Calling `client.tables()` directly therefore fails to resolve.
     * Reflective dispatch through the concrete runtime type bypasses this and returns the
     * GG9-only [IgniteTables] type (absent from `ignite-core`), which compiles unambiguously.
     */
    @Suppress("UNCHECKED_CAST")
    private fun gg9Tables(client: IgniteClient): IgniteTables =
        client.javaClass.getMethod("tables").invoke(client) as IgniteTables

    /**
     * Obtains the GG9 [IgniteTransactions] from the client via its concrete runtime type.
     * See [gg9Tables] for the rationale.
     */
    @Suppress("UNCHECKED_CAST")
    private fun gg9Transactions(client: IgniteClient): IgniteTransactions =
        client.javaClass.getMethod("transactions").invoke(client) as IgniteTransactions

    override fun read(cacheName: String, key: Any): ReadOutcome {
        TODO("Plan 7 Task 7")
    }

    override fun close() {
        client?.close()
        client = null
    }
}
