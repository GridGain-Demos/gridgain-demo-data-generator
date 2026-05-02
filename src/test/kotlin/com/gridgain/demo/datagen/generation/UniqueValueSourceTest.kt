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

    @Test
    fun `throws when the underlying space cannot satisfy uniqueness`() {
        // Tiny space: just two distinct options in a regex.
        val s = UniqueValueSource(expression = "#{regexify '[ab]'}", maxRetries = 5)
        s.next(ctx); s.next(ctx)
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("exhausted")
    }
}
