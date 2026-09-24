package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.WriteSyncMode
import org.apache.ignite.cache.CacheAtomicityMode
import org.apache.ignite.cache.CacheWriteSynchronizationMode
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * The cache configuration `provisioning: apply` sends to the cluster.
 *
 * Separated from [Gg8XmlProvisioner] so it can be asserted without a cluster — the apply path
 * itself needs a live GG8 and is env-gated, which meant nothing checked what it actually asked
 * for. It asked for no backups and the default write mode, whatever data.yaml said, because it
 * only ever set the name, the atomicity mode and the affinity key.
 */
class Gg8CacheConfigTest {

    private fun descriptor(
        backups: Int = 0,
        mode: WriteSyncMode = WriteSyncMode.PRIMARY_SYNC,
        affinityColumn: String? = null,
        transactional: Boolean = false,
    ) = SchemaDescriptor(
        schemaName = "customer",
        keyColumn = "id",
        affinityColumn = affinityColumn,
        columns = listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)),
        transactional = transactional,
        backups = backups,
        writeSynchronizationMode = mode,
    )

    @Test
    fun `backups and write synchronization reach the cluster`() {
        val cfg = Gg8CacheConfig.forDescriptor(
            descriptor(backups = 1, mode = WriteSyncMode.FULL_SYNC)
        )

        assertThat(cfg.name).isEqualTo("customer")
        assertThat(cfg.backups).isEqualTo(1)
        assertThat(cfg.writeSynchronizationMode)
            .describedAs("FULL_SYNC is what makes a put wait for its replica")
            .isEqualTo(CacheWriteSynchronizationMode.FULL_SYNC)
    }

    @Test
    fun `an unreplicated schema still asks for exactly what it used to`() {
        val cfg = Gg8CacheConfig.forDescriptor(descriptor())

        assertThat(cfg.backups).isEqualTo(0)
        assertThat(cfg.writeSynchronizationMode).isEqualTo(CacheWriteSynchronizationMode.PRIMARY_SYNC)
        assertThat(cfg.atomicityMode).isEqualTo(CacheAtomicityMode.ATOMIC)
    }

    @Test
    fun `every write sync mode maps to its GridGain counterpart`() {
        // core cannot reference GridGain's enum, so the mapping is hand-written and has to be
        // exhaustive — a missed case would silently fall back to a mode nobody asked for.
        val mapped = WriteSyncMode.entries.associateWith {
            Gg8CacheConfig.forDescriptor(descriptor(mode = it)).writeSynchronizationMode
        }

        assertThat(mapped).isEqualTo(
            mapOf(
                WriteSyncMode.PRIMARY_SYNC to CacheWriteSynchronizationMode.PRIMARY_SYNC,
                WriteSyncMode.FULL_SYNC to CacheWriteSynchronizationMode.FULL_SYNC,
                WriteSyncMode.FULL_ASYNC to CacheWriteSynchronizationMode.FULL_ASYNC,
            )
        )
    }

    @Test
    fun `statistics are enabled, whatever else the schema asks for`() {
        // GridGain caches default statistics OFF, and CachePuts/CacheGets — with every monitoring
        // dashboard panel that reads them — stay blank under heavy generated load as a result.
        // Nothing errors, so the only symptom is an empty graph.
        WriteSyncMode.entries.forEach { mode ->
            assertThat(Gg8CacheConfig.forDescriptor(descriptor(mode = mode)).isStatisticsEnabled)
                .describedAs("statistics must not depend on the replication settings")
                .isTrue()
        }
        assertThat(Gg8CacheConfig.forDescriptor(descriptor(transactional = true)).isStatisticsEnabled)
            .isTrue()
    }

    @Test
    fun `the transactional and affinity choices are still carried`() {
        val cfg = Gg8CacheConfig.forDescriptor(
            descriptor(transactional = true, affinityColumn = "customer_id")
        )

        assertThat(cfg.atomicityMode).isEqualTo(CacheAtomicityMode.TRANSACTIONAL)
        assertThat(cfg.keyConfiguration).isNotNull()
        assertThat(cfg.keyConfiguration!!.single().affinityKeyFieldName).isEqualTo("customer_id")
    }
}
