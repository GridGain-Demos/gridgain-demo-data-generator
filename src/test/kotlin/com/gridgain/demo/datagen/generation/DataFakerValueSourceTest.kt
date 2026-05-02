package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class DataFakerValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `evaluates a simple datafaker expression`() {
        val s = DataFakerValueSource(expression = "#{name.firstName}")
        val v = s.next(ctx) as String
        assertThat(v).isNotEmpty()
    }

    @Test
    fun `produces stable string output across multiple calls`() {
        val s = DataFakerValueSource(expression = "#{name.firstName}")
        repeat(20) {
            val v = s.next(ctx)
            assertThat(v).isInstanceOf(String::class.java)
        }
    }

    @Test
    fun `wraps datafaker errors in MisconfigurationException with the expression`() {
        val s = DataFakerValueSource(expression = "#{not.a.real.provider}")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("not.a.real.provider")
    }
}
