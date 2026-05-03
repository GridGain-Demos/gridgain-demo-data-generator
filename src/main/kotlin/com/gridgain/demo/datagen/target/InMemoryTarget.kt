package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

data class ReadCall(val cacheName: String, val key: Any)

class InMemoryTarget : Target {
    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = false

    private val _writes: MutableList<BusinessEvent> = mutableListOf()
    val writes: List<BusinessEvent> get() = _writes

    private val _reads: MutableList<ReadCall> = mutableListOf()
    val reads: List<ReadCall> get() = _reads

    override fun write(event: BusinessEvent): WriteOutcome {
        _writes.add(event)
        return WriteOutcome(success = true)
    }

    override fun read(cacheName: String, key: Any): ReadOutcome {
        _reads.add(ReadCall(cacheName, key))
        return ReadOutcome(success = true, value = "in-memory-stub")
    }
}
