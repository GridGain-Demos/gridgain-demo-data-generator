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

    @Test fun `currentNext exposes the next value to be emitted`() {
        val s = SequenceValueSource(start = 10, step = 3)
        assertThat(s.currentNext).isEqualTo(10L)
        s.next(ctx)
        assertThat(s.currentNext).isEqualTo(13L)
    }

    @Test fun `initialPosition overrides start`() {
        val s = SequenceValueSource(start = 10, step = 3, initialPosition = 100)
        assertThat(s.currentNext).isEqualTo(100L)
        val first = s.next(ctx)
        assertThat(first).isEqualTo(100L)
        assertThat(s.currentNext).isEqualTo(103L)
    }

    @Test fun `initialPosition of null falls back to start`() {
        val s = SequenceValueSource(start = 10, step = 3, initialPosition = null)
        assertThat(s.currentNext).isEqualTo(10L)
    }
}
