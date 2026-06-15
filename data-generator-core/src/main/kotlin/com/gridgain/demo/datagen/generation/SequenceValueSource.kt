package com.gridgain.demo.datagen.generation

/**
 * Distributed-mode partition stripe applied to a [SequenceValueSource]. With N workers
 * sharing the keyspace, worker `partitionId` (0..partitionCount-1) emits values starting
 * at `start + partitionId * step` and steps by `step * partitionCount`. The union of all
 * workers' sequences reproduces the single-pod sequence, partitioned by residue class
 * mod `partitionCount`, so no key is ever emitted twice across the cluster.
 */
data class PartitionStripe(val partitionId: Int, val partitionCount: Int) {
    init {
        require(partitionCount > 0) { "partitionCount must be > 0, got $partitionCount" }
        require(partitionId in 0 until partitionCount) {
            "partitionId $partitionId out of range [0, $partitionCount)"
        }
    }
}

class SequenceValueSource(
    start: Long,
    step: Long,
    initialPosition: Long? = null,
    partitionStripe: PartitionStripe? = null,
) : ValueSource {

    private val effectiveStep: Long
    private var nextValue: Long

    init {
        if (partitionStripe == null) {
            effectiveStep = step
            nextValue = initialPosition ?: start
        } else {
            effectiveStep = step * partitionStripe.partitionCount
            val base = initialPosition ?: start
            nextValue = base + partitionStripe.partitionId * step
        }
    }

    /** The value that will be returned by the next call to `next()`. Used by `ValueSourceFactory`
     *  to capture sequence cursors at end of run for `state.yaml`. */
    val currentNext: Long get() = nextValue

    override fun next(ctx: GenerationContext): Any {
        val out = nextValue
        nextValue += effectiveStep
        return out
    }
}
