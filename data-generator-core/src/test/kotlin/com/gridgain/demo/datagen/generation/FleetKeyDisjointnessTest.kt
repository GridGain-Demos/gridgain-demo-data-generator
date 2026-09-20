package com.gridgain.demo.datagen.generation

import net.datafaker.Faker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Power lab produced 0.50 new cache entries per put with two instances at `concurrency: 32`,
 * and exactly 32 of every 64 consecutive keys existed. That is the signature of both processes
 * emitting the *same* 32 residue classes: duplicated keys, and GridGain serialising on the
 * contended entries — 1.5 s latencies while every machine sat at 0% CPU.
 *
 * These pin the arithmetic the whole fleet depends on. The plugin was verified separately (its
 * rendered `run.env` carried `--instance-index 0` and `1` correctly), so what is under test here
 * is purely what a process does with the stripe once it has it.
 */
class FleetKeyDisjointnessTest {

    private val ctx = GenerationContext(faker = Faker())

    /** Keys a process emits, given its instance stripe, across all its worker threads. */
    private fun keysFor(
        instanceIndex: Int,
        instanceCount: Int,
        concurrency: Int,
        perWorker: Int,
        start: Long = 1L,
        step: Long = 1L,
        restoredCursor: Long? = null,
    ): List<Long> = (0 until concurrency).flatMap { worker ->
        val src = SequenceValueSource(
            start = start,
            step = step,
            initialPosition = restoredCursor,
            partitionStripe = workerStripe(
                processStripe = PartitionStripe(instanceIndex, instanceCount),
                workerIndex = worker,
                concurrency = concurrency,
            ),
        )
        (0 until perWorker).map { src.next(ctx) as Long }
    }

    @Test
    fun `two instances at concurrency 32 emit disjoint keys`() {
        val a = keysFor(instanceIndex = 0, instanceCount = 2, concurrency = 32, perWorker = 50)
        val b = keysFor(instanceIndex = 1, instanceCount = 2, concurrency = 32, perWorker = 50)

        val overlap = a.toSet() intersect b.toSet()
        assertTrue(
            overlap.isEmpty(),
            "the two instances share ${overlap.size} keys — that is the duplication that makes " +
                "GridGain serialise on contended entries; first few: ${overlap.take(5)}",
        )
    }

    @Test
    fun `the fleet's keys are contiguous, leaving no residue class unwritten`() {
        // 50% density over a window was the observed symptom. The union of every worker of every
        // instance must tile the integers with no gaps.
        val all = (keysFor(0, 2, 32, 50) + keysFor(1, 2, 32, 50)).sorted()

        assertEquals(all.size, all.toSet().size, "no key may be emitted twice across the fleet")
        val window = all.filter { it in 100L..163L }
        assertEquals(
            64, window.size,
            "keys 100..163 is one full stride of 64; every one should be written exactly once, " +
                "got ${window.size} — density ${window.size * 100 / 64}%",
        )
    }

    @Test
    fun `a restored cursor shared by every worker still yields disjoint keys`() {
        // WorkerGenerators hands the same loadedState to every worker, so a resumed run must not
        // collapse the stripes onto each other.
        val a = keysFor(0, 2, 32, 50, restoredCursor = 200_000L)
        val b = keysFor(1, 2, 32, 50, restoredCursor = 200_000L)

        assertTrue((a.toSet() intersect b.toSet()).isEmpty(), "a resumed fleet must stay disjoint")
        assertEquals((a + b).size, (a + b).toSet().size)
    }
}
