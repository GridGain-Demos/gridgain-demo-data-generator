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

    // Partition striping: distributed-mode workers carry a PartitionStripe so two pods
    // never emit the same key. With partition_count workers, each worker's sequence is
    // offset by partitionId and stepped by partition_count * step.

    @Test fun `partitioned worker 0 of 3 emits stride-3 sequence starting at start`() {
        val s = SequenceValueSource(
            start = 0, step = 1,
            partitionStripe = PartitionStripe(partitionId = 0, partitionCount = 3),
        )
        assertThat(s.next(ctx)).isEqualTo(0L)
        assertThat(s.next(ctx)).isEqualTo(3L)
        assertThat(s.next(ctx)).isEqualTo(6L)
    }

    @Test fun `partitioned worker 1 of 3 emits stride-3 sequence starting at start+step`() {
        val s = SequenceValueSource(
            start = 0, step = 1,
            partitionStripe = PartitionStripe(partitionId = 1, partitionCount = 3),
        )
        assertThat(s.next(ctx)).isEqualTo(1L)
        assertThat(s.next(ctx)).isEqualTo(4L)
        assertThat(s.next(ctx)).isEqualTo(7L)
    }

    @Test fun `partitioned worker 2 of 3 emits the third coset`() {
        val s = SequenceValueSource(
            start = 0, step = 1,
            partitionStripe = PartitionStripe(partitionId = 2, partitionCount = 3),
        )
        assertThat(s.next(ctx)).isEqualTo(2L)
        assertThat(s.next(ctx)).isEqualTo(5L)
        assertThat(s.next(ctx)).isEqualTo(8L)
    }

    @Test fun `union of three partitioned workers reproduces single-pod sequence`() {
        val s0 = SequenceValueSource(start = 100, step = 1,
            partitionStripe = PartitionStripe(0, 3))
        val s1 = SequenceValueSource(start = 100, step = 1,
            partitionStripe = PartitionStripe(1, 3))
        val s2 = SequenceValueSource(start = 100, step = 1,
            partitionStripe = PartitionStripe(2, 3))
        val emitted = (0 until 3).flatMap {
            listOf(s0.next(ctx) as Long, s1.next(ctx) as Long, s2.next(ctx) as Long)
        }
        assertThat(emitted).containsExactlyInAnyOrderElementsOf((100L..108L).toList())
    }

    @Test fun `partition stripe respects step magnitude`() {
        val s0 = SequenceValueSource(start = 0, step = 5,
            partitionStripe = PartitionStripe(0, 2))
        val s1 = SequenceValueSource(start = 0, step = 5,
            partitionStripe = PartitionStripe(1, 2))
        assertThat(listOf(s0.next(ctx), s0.next(ctx), s0.next(ctx)))
            .containsExactly(0L, 10L, 20L)
        assertThat(listOf(s1.next(ctx), s1.next(ctx), s1.next(ctx)))
            .containsExactly(5L, 15L, 25L)
    }
}
