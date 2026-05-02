package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Random
import kotlin.test.Test

class KeySuffixValueSourceTest {

    @Test
    fun `appends separator and random suffix to base column value`() {
        val ctx = GenerationContext(
            faker = Faker(),
            rowSoFar = mutableMapOf("customer_id" to "C123"),
        )
        val s = KeySuffixValueSource(baseColumn = "customer_id", separator = "-", length = 6, random = Random(1L))
        val v = s.next(ctx) as String
        assertThat(v).startsWith("C123-")
        assertThat(v.substringAfter("-")).hasSize(6).matches("^[a-z0-9]+$")
    }

    @Test
    fun `produces distinct suffixes across calls`() {
        val ctx = GenerationContext(
            faker = Faker(),
            rowSoFar = mutableMapOf("base" to "X"),
        )
        val s = KeySuffixValueSource(baseColumn = "base", separator = "-", length = 8, random = Random(42L))
        val seen = (1..50).map { s.next(ctx) as String }.toSet()
        assertThat(seen.size).isEqualTo(50)
    }

    @Test
    fun `missing base column produces remediation`() {
        val ctx = GenerationContext(faker = Faker())
        val s = KeySuffixValueSource(baseColumn = "absent", separator = "-", length = 6, random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("absent")
            .hasMessageContaining("declared earlier")
    }
}
