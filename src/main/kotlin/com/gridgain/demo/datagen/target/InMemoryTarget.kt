package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent

class InMemoryTarget : Target {
    override val supportsReads: Boolean = true
    override val supportsTransactions: Boolean = false

    private val _writes: MutableList<BusinessEvent> = mutableListOf()
    val writes: List<BusinessEvent> get() = _writes

    override fun write(event: BusinessEvent): WriteOutcome {
        _writes.add(event)
        return WriteOutcome(success = true)
    }
}
