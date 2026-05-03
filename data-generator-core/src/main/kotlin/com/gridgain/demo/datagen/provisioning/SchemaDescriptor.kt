package com.gridgain.demo.datagen.provisioning

/** `transactional` is true when scenario.transactionScope == BUSINESS_EVENT — drives
 *  GG8 CacheAtomicityMode.TRANSACTIONAL; carries no DDL effect for GG9.
 *  `affinityColumn` is null when no column declares `affinity: true`. */
data class SchemaDescriptor(
    val schemaName: String,
    val keyColumn: String,
    val affinityColumn: String?,
    val columns: List<ColumnDescriptor>,
    val transactional: Boolean,
)
