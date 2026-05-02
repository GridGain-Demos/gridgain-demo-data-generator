package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class WeightedChoiceValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `single choice always returns that value`() {
        val s = WeightedChoiceValueSource(listOf(WeightedChoice("only", 1.0)), seed = 1L)
        repeat(10) { assertThat(s.next(ctx)).isEqualTo("only") }
    }

    @Test
    fun `weighted distribution converges to declared shares within tolerance`() {
        val s = WeightedChoiceValueSource(
            listOf(WeightedChoice("a", 0.7), WeightedChoice("b", 0.3)),
            seed = 42L,
        )
        val counts = mutableMapOf("a" to 0, "b" to 0)
        repeat(10_000) { counts.merge(s.next(ctx) as String, 1) { x, y -> x + y } }
        assertThat(counts["a"]!! / 10_000.0).isBetween(0.66, 0.74)
        assertThat(counts["b"]!! / 10_000.0).isBetween(0.26, 0.34)
    }

    @Test
    fun `empty choices is rejected at construction`() {
        assertThatThrownBy { WeightedChoiceValueSource(emptyList(), seed = 1L) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("at least one choice")
    }

    @Test
    fun `non-positive weight is rejected at construction`() {
        assertThatThrownBy {
            WeightedChoiceValueSource(listOf(WeightedChoice("x", 0.0)), seed = 1L)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("weight")
    }
}
