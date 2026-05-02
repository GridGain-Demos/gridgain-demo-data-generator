package com.gridgain.demo.datagen.generation

import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import java.util.Random
import kotlin.test.Test

class NullRateApplicatorTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `null rate of zero never produces null`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 0.0, random = Random(1L))
        repeat(1000) { assertThat(applicator.next(ctx)).isNotNull() }
    }

    @Test
    fun `null rate of one always produces null`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 1.0, random = Random(1L))
        repeat(100) { assertThat(applicator.next(ctx)).isNull() }
    }

    @Test
    fun `null rate of one half lands within statistical tolerance`() {
        val inner = SequenceValueSource(start = 0, step = 1)
        val applicator = NullRateApplicator(inner, nullRate = 0.5, random = Random(42L))
        val nullCount = (1..10_000).count { applicator.next(ctx) == null }
        assertThat(nullCount.toDouble() / 10_000).isBetween(0.46, 0.54)
    }
}
