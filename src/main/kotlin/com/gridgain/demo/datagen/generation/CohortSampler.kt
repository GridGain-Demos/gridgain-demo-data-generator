package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class CohortSampler(seed: Long) {

    private val random: Random = Random(seed)

    fun assign(parentCount: Int, buckets: List<CohortBucket>): IntArray {
        val totalShare = buckets.sumOf { it.share }
        if (kotlin.math.abs(totalShare - 1.0) > 0.001) {
            throw MisconfigurationException(
                "cohort_buckets shares must sum to 1.0 (within 0.001 tolerance); " +
                "got ${"%.3f".format(totalShare)}. " +
                "Adjust the share values so they total 1.0."
            )
        }
        val slotCounts = allocateSlots(parentCount, buckets)
        val slots = mutableListOf<Int>()
        for (i in buckets.indices) {
            repeat(slotCounts[i]) { slots.add(buckets[i].multiplier) }
        }
        slots.shuffle(random)
        return slots.toIntArray()
    }

    private fun allocateSlots(parentCount: Int, buckets: List<CohortBucket>): IntArray {
        val exact = buckets.map { it.share * parentCount }
        val counts = IntArray(buckets.size) { exact[it].toInt() }
        val remainders = DoubleArray(buckets.size) { exact[it] - counts[it] }
        var allocated = counts.sum()
        while (allocated < parentCount) {
            val idx = remainders.indices.maxByOrNull { remainders[it] }!!
            counts[idx]++
            remainders[idx] = -1.0
            allocated++
        }
        return counts
    }
}
