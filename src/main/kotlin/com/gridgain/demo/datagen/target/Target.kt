package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class WriteOutcome(
    val success: Boolean,
    val error: Throwable? = null,
)

interface Target {
    val supportsReads: Boolean
    val supportsTransactions: Boolean
    fun write(event: BusinessEvent): WriteOutcome
}
