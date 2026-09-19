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

/**
 * Folds the two independent divisions of the key space into the single [PartitionStripe] that
 * [SequenceValueSource] can honour: the slice this *process* owns (from `--instance-index` or the
 * distributed Coordinator) and the slice each *thread* within it owns (from the scenario's
 * `concurrency`).
 *
 * Worker `w` of `concurrency` inside process stripe `(i, n)` becomes `(i * concurrency + w,
 * n * concurrency)`, which tiles the sequence exactly: every thread of every process owns a
 * distinct residue class and their union has no gaps. Sharing a counter between threads instead
 * would put a contended atomic on the hot path of the one loop whose throughput is the point.
 *
 * Returns null — rather than the identity stripe `(0, 1)` — for an unstriped single-threaded run,
 * so that configuration stays byte-for-byte today's behaviour and nothing announces striping that
 * is not happening.
 *
 * **Every process in a fleet must use the same [concurrency].** Disjointness depends on all
 * workers agreeing on `partitionCount`; mixing (say) a 1-thread and a 4-thread process across two
 * machines produces stripes of different widths that overlap, and GridGain sees duplicate primary
 * keys. Uniformity holds by construction today because every instance of a run is launched from
 * the same ops.yaml.
 */
fun workerStripe(processStripe: PartitionStripe?, workerIndex: Int, concurrency: Int): PartitionStripe? {
    if (processStripe == null && concurrency == 1) return null
    val base = processStripe ?: PartitionStripe(partitionId = 0, partitionCount = 1)
    return PartitionStripe(
        partitionId = base.partitionId * concurrency + workerIndex,
        partitionCount = base.partitionCount * concurrency,
    )
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
