package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.WriteSyncMode
import org.apache.ignite.cache.CacheAtomicityMode
import org.apache.ignite.cache.CacheKeyConfiguration
import org.apache.ignite.cache.CacheWriteSynchronizationMode
import org.apache.ignite.client.ClientCacheConfiguration

/**
 * One [SchemaDescriptor] to the cache configuration `provisioning: apply` sends to the cluster.
 *
 * Pulled out of [Gg8XmlProvisioner] so it can be asserted without a live GG8 — the apply path is
 * env-gated, so nothing checked what it actually requested. It requested no backups and the
 * default write mode however data.yaml was written, because it only ever set the name, the
 * atomicity mode and the affinity key.
 *
 * `readFromBackup` is deliberately left at GridGain's default of true: with a backup on every
 * other node of a two-node cluster, each node holds a copy of every partition and can answer a
 * read locally, which is the behaviour a replicated demo is meant to show.
 */
internal object Gg8CacheConfig {

    fun forDescriptor(d: SchemaDescriptor): ClientCacheConfiguration =
        ClientCacheConfiguration().apply {
            setName(d.schemaName)
            setAtomicityMode(
                if (d.transactional) CacheAtomicityMode.TRANSACTIONAL else CacheAtomicityMode.ATOMIC
            )
            setBackups(d.backups)
            setWriteSynchronizationMode(writeSyncOf(d.writeSynchronizationMode))
            // GridGain caches default statistics OFF, which leaves the cluster's cache-ops metrics
            // (CachePuts, CacheGets) — and every dashboard panel that reads them — blank however
            // hard the generator drives the cluster. Nothing errors and the load is real, so the
            // only symptom is an empty graph, which reads as "the generator is not working".
            //
            // Unconditional rather than configurable: this is a demo generator whose caches exist
            // to be watched, and per-cache statistics cost is negligible next to the write path.
            setStatisticsEnabled(true)
            d.affinityColumn?.let { setKeyConfiguration(CacheKeyConfiguration("java.lang.Object", it)) }
        }

    /**
     * core cannot reference GridGain's enum, so the mapping is written out here. Exhaustive `when`
     * without an `else`, so adding a mode to [WriteSyncMode] fails the compile rather than
     * silently falling back to one nobody asked for.
     */
    private fun writeSyncOf(mode: WriteSyncMode): CacheWriteSynchronizationMode = when (mode) {
        WriteSyncMode.PRIMARY_SYNC -> CacheWriteSynchronizationMode.PRIMARY_SYNC
        WriteSyncMode.FULL_SYNC -> CacheWriteSynchronizationMode.FULL_SYNC
        WriteSyncMode.FULL_ASYNC -> CacheWriteSynchronizationMode.FULL_ASYNC
    }
}
