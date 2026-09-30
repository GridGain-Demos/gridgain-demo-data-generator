package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.KeyDistribution
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random
import kotlin.math.pow

/**
 * Draws a key index from a bounded key space.
 *
 * The key space is what makes a run *repeatable against the same rows*, and it is the single
 * addition that turns the generator from a data emitter into something that can answer a
 * benchmark's question. Without it write keys came from an unbounded `sequence` and read keys were
 * sampled from a registry of whatever this process happened to have written this run — so a get
 * could only ever hit a row the same process had just put, two runs never addressed the same data,
 * and a read-heavy scenario could miss on nearly every operation while reporting a healthy rate.
 *
 * Selectors return an **index**, not a key. Turning an index into the key a schema actually uses is
 * [com.gridgain.demo.datagen.config.ColumnSpec]'s business, because the key column's type comes from
 * its value source.
 *
 * Implementations are **not** thread-safe and hold no state that workers share; each worker owns one
 * and passes its own [Random]. That is what keeps a run reproducible from a seed — a shared selector
 * would interleave draws differently on every run.
 */
internal interface KeySelector {

    /** An index in `[0, size)`. */
    fun next(random: Random): Long

    companion object {
        fun forDistribution(distribution: KeyDistribution, size: Long): KeySelector {
            if (size < 1) {
                throw MisconfigurationException(
                    "key_space.size must hold at least 1 key; got $size. It is the number of " +
                        "distinct keys the scenario addresses — set it to the row count the run " +
                        "should work against."
                )
            }
            return when (distribution) {
                KeyDistribution.UNIFORM -> UniformKeySelector(size)
                KeyDistribution.ZIPFIAN -> ZipfianKeySelector(size)
                KeyDistribution.LATEST -> LatestKeySelector(size)
            }
        }
    }
}

/**
 * Every key equally likely — the honest default, and the one Yardstick's `-r/--range` implies.
 *
 * It is also the *worst case* for a cache, because nothing stays hot enough to be worth keeping
 * close. A demo that wants to show caching working well wants [ZipfianKeySelector].
 */
private class UniformKeySelector(private val size: Long) : KeySelector {
    override fun next(random: Random): Long = random.nextLong(size)
}

/**
 * A hot head and a long cold tail — the shape real access patterns take.
 *
 * The classic Gray et al. generator, as used by YCSB: draw `u` uniformly, then invert the Zipf CDF.
 * [THETA] of 0.99 is YCSB's own default and is chosen because it is *near* 1 without being 1, where
 * the harmonic series diverges and the constants below stop being computable.
 *
 * The three constants are derived once at construction: over a large space the sums they approximate
 * cost far more than a draw does, and a per-draw recomputation would make the selector itself the
 * bottleneck being measured.
 */
private class ZipfianKeySelector(private val size: Long) : KeySelector {

    private val zetan: Double = zeta(size, THETA)
    private val alpha: Double = 1.0 / (1.0 - THETA)
    private val eta: Double =
        (1.0 - (2.0 / size).pow(1.0 - THETA)) / (1.0 - zeta(2, THETA) / zetan)

    override fun next(random: Random): Long {
        if (size == 1L) return 0L
        val u = random.nextDouble()
        val uz = u * zetan
        if (uz < 1.0) return 0L
        if (uz < 1.0 + 0.5.pow(THETA)) return 1L
        val index = ((size) * ((eta * u) - eta + 1.0).pow(alpha)).toLong()
        return index.coerceIn(0L, size - 1)
    }

    private companion object {
        /** YCSB's default skew. Must stay strictly below 1: at 1 the series does not converge. */
        const val THETA = 0.99

        fun zeta(n: Long, theta: Double): Double {
            var sum = 0.0
            for (i in 1..n) sum += 1.0 / i.toDouble().pow(theta)
            return sum
        }
    }
}

/**
 * Skewed toward the **most recently inserted** keys — a zipfian head mirrored onto the top of the
 * space.
 *
 * This is the shape of a feed, a ledger tail or an event stream: the newest rows take almost all the
 * reads and the old ones are nearly cold. It stresses a cluster completely differently from
 * [ZipfianKeySelector], whose hot keys are fixed for the life of the run — here the hot set moves
 * with the write cursor, so a cache is constantly re-warming.
 */
private class LatestKeySelector(size: Long) : KeySelector {
    private val size = size
    private val zipfian = ZipfianKeySelector(size)
    override fun next(random: Random): Long = size - 1 - zipfian.next(random)
}
