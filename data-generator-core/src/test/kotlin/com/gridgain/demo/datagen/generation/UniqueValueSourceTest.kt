package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class UniqueValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `produces distinct values across many calls`() {
        val s = UniqueValueSource(expression = "#{internet.username}", maxRetries = 100)
        val seen = (1..200).map { s.next(ctx) as String }.toSet()
        assertThat(seen.size).isEqualTo(200)
    }

    /**
     * Three draws from a two-value space must exhaust it.
     *
     * Asserted over the whole sequence rather than by seeding the first two calls and demanding the
     * third throw. The draws are random, so the *second* call can exhaust its retries too — it has
     * to draw the one remaining value within `maxRetries` coin flips, and a run of five identical
     * flips has a 1-in-64 chance. That made this test fail roughly once in every dozen runs for
     * reasons that had nothing to do with the code under test. Which call throws is not the
     * behaviour worth pinning; that the space runs out is.
     */
    @Test
    fun `throws when the underlying space cannot satisfy uniqueness`() {
        // Tiny space: just two distinct options in a regex.
        val s = UniqueValueSource(expression = "#{regexify '[ab]'}", maxRetries = 5)
        assertThatThrownBy { repeat(3) { s.next(ctx) } }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("exhausted")
    }
}
