package com.gridgain.demo.datagen.generation

import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class SequenceValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `produces values starting at start with step`() {
        val s = SequenceValueSource(start = 10, step = 3)
        assertThat(s.next(ctx)).isEqualTo(10L)
        assertThat(s.next(ctx)).isEqualTo(13L)
        assertThat(s.next(ctx)).isEqualTo(16L)
    }

    @Test
    fun `negative step decrements`() {
        val s = SequenceValueSource(start = 0, step = -1)
        assertThat(s.next(ctx)).isEqualTo(0L)
        assertThat(s.next(ctx)).isEqualTo(-1L)
    }
}
