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
        val cumulative: List<Pair<Double, Int>> =
            buckets.runningFold(0.0 to 0) { acc, b -> (acc.first + b.share) to b.multiplier }
                .drop(1)
        val out = IntArray(parentCount)
        for (i in 0 until parentCount) {
            val r = random.nextDouble()
            // Defensive: r could theoretically reach the last threshold; pick last bucket.
            out[i] = cumulative.firstOrNull { r < it.first }?.second ?: cumulative.last().second
        }
        return out
    }
}
