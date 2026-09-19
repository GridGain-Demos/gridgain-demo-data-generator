package com.gridgain.demo.datagen.generation

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * Composition of the two independent ways the key space is divided: across processes (a
 * `PartitionStripe` from `--instance-index` or the distributed Coordinator) and, within one
 * process, across the worker threads a `concurrency` setting asks for.
 *
 * The two must compose into a single stripe, because [SequenceValueSource] knows how to honour
 * exactly one. Getting this wrong emits duplicate primary keys, which is why it is tested as
 * pure arithmetic rather than only through a running scenario.
 */
class WorkerStripeTest {

    @Test
    fun `a lone worker in an unstriped process leaves the key space unstriped`() {
        assertThat(workerStripe(processStripe = null, workerIndex = 0, concurrency = 1))
            .describedAs(
                "concurrency 1 without an --instance-index must be byte-for-byte today's " +
                    "single-threaded run: no stripe at all, not the identity stripe. A stripe " +
                    "here would have ScenarioRunnerCli announce striping that is not happening."
            )
            .isNull()
    }

    @Test
    fun `four workers in an unstriped process tile the whole key space`() {
        val stripes = (0 until 4).map { workerStripe(processStripe = null, workerIndex = it, concurrency = 4) }

        assertThat(stripes.map { it!!.partitionId })
            .describedAs("each worker owns a distinct residue class")
            .containsExactly(0, 1, 2, 3)
        assertThat(stripes.map { it!!.partitionCount })
            .describedAs("every worker strides by the total worker count")
            .containsOnly(4)
    }

    @Test
    fun `a worker inherits its slice of an already-striped process`() {
        val stripes = (0 until 3).map {
            workerStripe(processStripe = PartitionStripe(1, 2), workerIndex = it, concurrency = 3)
        }

        assertThat(stripes.map { it!!.partitionId }).containsExactly(3, 4, 5)
        assertThat(stripes.map { it!!.partitionCount }).containsOnly(6)
    }

    @Test
    fun `concurrency of one hands a striped process its stripe unchanged`() {
        assertThat(workerStripe(processStripe = PartitionStripe(1, 3), workerIndex = 0, concurrency = 1))
            .isEqualTo(PartitionStripe(1, 3))
    }

    /**
     * The whole point of the arithmetic: two machines each running several threads must never
     * write the same primary key. Asserted on real [SequenceValueSource]s rather than on stripe
     * ids, because it is the emitted values that collide in GridGain.
     */
    @Test
    fun `every thread of every process emits a disjoint key space`() {
        val processCount = 2
        val concurrency = 3
        val ctx = GenerationContext(net.datafaker.Faker())

        val emitted = (0 until processCount).flatMap { processIndex ->
            (0 until concurrency).flatMap { worker ->
                val source = SequenceValueSource(
                    start = 0, step = 1,
                    partitionStripe = workerStripe(
                        processStripe = PartitionStripe(processIndex, processCount),
                        workerIndex = worker,
                        concurrency = concurrency,
                    ),
                )
                (0 until 10).map { source.next(ctx) as Long }
            }
        }

        assertThat(emitted)
            .describedAs("6 threads x 10 ops must be 60 distinct keys, with no duplicates")
            .doesNotHaveDuplicates()
            .hasSize(60)
        assertThat(emitted)
            .describedAs("and together they tile the sequence with no gaps")
            .containsExactlyInAnyOrderElementsOf((0L until 60L).toList())
    }
}
