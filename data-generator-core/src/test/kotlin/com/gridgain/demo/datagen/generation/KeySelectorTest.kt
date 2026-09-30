package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.KeyDistribution
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Random
import kotlin.test.Test

/**
 * Key selection over a bounded key space.
 *
 * Until now the generator had no bounded key space at all: write keys came from an unbounded
 * `sequence` value source, and read keys were sampled uniformly from a registry holding only what
 * this process had written during this run. That made three things impossible — Yardstick's
 * `-r/--range`, a get that reliably hits, and two runs that address the same rows — and it is why a
 * read-heavy run could report a healthy rate while missing on nearly every operation.
 *
 * The distributions are asserted by their *shape*, not by exact draws. A distribution is a
 * statistical claim, and pinning it to a seed would assert the implementation rather than the
 * property, so these tests state what each distribution must do differently from the others.
 */
class KeySelectorTest {

    private val size = 10_000L
    private val draws = 200_000

    private fun sample(distribution: KeyDistribution, seed: Long = 42L): LongArray {
        val selector = KeySelector.forDistribution(distribution, size)
        val random = Random(seed)
        return LongArray(draws) { selector.next(random) }
    }

    // -----------------------------------------------------------------------
    // Every distribution has to stay inside the space
    // -----------------------------------------------------------------------

    @Test
    fun `every distribution draws only keys inside the space`() {
        KeyDistribution.entries.forEach { distribution ->
            val keys = sample(distribution)
            assertThat(keys.min())
                .describedAs("%s produced a key below zero", distribution).isGreaterThanOrEqualTo(0L)
            assertThat(keys.max())
                .describedAs(
                    "%s produced a key at or above the space size; a key outside the space can " +
                        "never be read back and turns a put benchmark into a miss generator",
                    distribution,
                )
                .isLessThan(size)
        }
    }

    @Test
    fun `a single-key space is legal and always yields that key`() {
        val selector = KeySelector.forDistribution(KeyDistribution.ZIPFIAN, 1L)
        val random = Random(1L)
        assertThat(LongArray(1_000) { selector.next(random) }.toSet()).containsExactly(0L)
    }

    @Test
    fun `a key space must hold at least one key`() {
        assertThatThrownBy { KeySelector.forDistribution(KeyDistribution.UNIFORM, 0L) }
            .hasMessageContaining("at least 1")
    }

    // -----------------------------------------------------------------------
    // Each distribution has to be distinguishable from the others
    // -----------------------------------------------------------------------

    @Test
    fun `uniform spreads draws evenly across the whole space`() {
        val keys = sample(KeyDistribution.UNIFORM)
        val buckets = LongArray(10)
        keys.forEach { buckets[(it * 10 / size).toInt()]++ }

        val expected = draws / 10.0
        buckets.forEach {
            assertThat(it.toDouble())
                .describedAs("uniform must not favour any tenth of the space; buckets were %s",
                    buckets.toList())
                .isCloseTo(expected, org.assertj.core.data.Percentage.withPercentage(10.0))
        }
    }

    @Test
    fun `zipfian concentrates most draws on a small head`() {
        val keys = sample(KeyDistribution.ZIPFIAN)
        val hotTenth = keys.count { it < size / 10 }.toDouble() / draws

        assertThat(hotTenth)
            .describedAs(
                "a hot-key distribution that is not hot is just a slow uniform; the first tenth of " +
                    "the space took only %.1f%% of draws",
                hotTenth * 100,
            )
            .isGreaterThan(0.30)
        assertThat(keys.toSet().size)
            .describedAs("zipfian must still reach the cold tail, not degenerate onto a few keys")
            .isGreaterThan(size.toInt() / 2)
    }

    @Test
    fun `latest concentrates draws on the most recently inserted keys`() {
        val keys = sample(KeyDistribution.LATEST)
        val newestTenth = keys.count { it >= size - size / 10 }.toDouble() / draws
        val oldestTenth = keys.count { it < size / 10 }.toDouble() / draws

        assertThat(newestTenth)
            .describedAs("'latest' must favour the high end of the space — the recent inserts")
            .isGreaterThan(0.30)
        assertThat(newestTenth)
            .describedAs("'latest' is zipfian mirrored; if the ends match it is not skewed at all")
            .isGreaterThan(oldestTenth * 5)
    }

    @Test
    fun `the three distributions are genuinely different from one another`() {
        fun headShare(d: KeyDistribution) =
            sample(d).count { it < size / 10 }.toDouble() / draws

        val uniform = headShare(KeyDistribution.UNIFORM)
        val zipfian = headShare(KeyDistribution.ZIPFIAN)
        val latest = headShare(KeyDistribution.LATEST)

        assertThat(zipfian).describedAs("zipfian must be hotter at the head than uniform")
            .isGreaterThan(uniform * 2)
        assertThat(latest).describedAs("latest must be colder at the head than uniform")
            .isLessThan(uniform)
    }

    // -----------------------------------------------------------------------
    // Reproducibility — a benchmark that cannot be repeated is an anecdote
    // -----------------------------------------------------------------------

    @Test
    fun `the same seed reproduces the same key sequence`() {
        KeyDistribution.entries.forEach { distribution ->
            assertThat(sample(distribution, seed = 7L))
                .describedAs("%s must be reproducible from a seed, or no two runs compare",
                    distribution)
                .isEqualTo(sample(distribution, seed = 7L))
        }
    }
}
