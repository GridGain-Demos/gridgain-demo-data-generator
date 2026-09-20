package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.WriteSyncMode

/** `transactional` is true when scenario.transactionScope == BUSINESS_EVENT — drives
 *  GG8 CacheAtomicityMode.TRANSACTIONAL; carries no DDL effect for GG9.
 *  `affinityColumn` is null when no column declares `affinity: true`. */
data class SchemaDescriptor(
    val schemaName: String,
    val keyColumn: String,
    val affinityColumn: String?,
    val columns: List<ColumnDescriptor>,
    val transactional: Boolean,
    /** Copies beyond the primary. From `SchemaSpec.backups`; 0 is one copy, no redundancy. */
    val backups: Int = 0,
    /** When a write may return, relative to those copies. From `SchemaSpec`. */
    val writeSynchronizationMode: WriteSyncMode = WriteSyncMode.PRIMARY_SYNC,
)
