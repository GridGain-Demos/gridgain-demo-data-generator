package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class WriteOutcome(
    val success: Boolean,
    val error: Throwable? = null,
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
