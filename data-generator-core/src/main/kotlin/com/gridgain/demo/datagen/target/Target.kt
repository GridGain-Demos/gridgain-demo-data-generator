package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

/**
 * Per-write transaction outcome. `NONE` means the target ran outside any transaction
 * (atomic put). `COMMITTED` and `ROLLED_BACK` are populated only when the target wraps
 * the event in a transaction (today: `transaction_scope: business_event`). Drives
 * `ScenarioRunner.tick`'s emission of `op=tx_commit` / `op=tx_rollback` metrics on top
 * of the underlying `op=put` (closes F12).
 */
enum class TransactionOutcome { NONE, COMMITTED, ROLLED_BACK }

data class WriteOutcome(
    val success: Boolean,
    val error: Throwable? = null,
    val transactionOutcome: TransactionOutcome = TransactionOutcome.NONE,
)

data class ReadOutcome(
    val success: Boolean,
    val value: Any? = null,
    val error: Throwable? = null,
)

interface Target {
    val supportsReads: Boolean
    val supportsTransactions: Boolean
    fun write(event: BusinessEvent): WriteOutcome
    fun read(cacheName: String, key: Any): ReadOutcome
}
