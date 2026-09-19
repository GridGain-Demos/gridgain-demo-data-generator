package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.SequenceState
import net.datafaker.Faker
import java.nio.file.Path

/**
 * The generators a run's worker threads emit from: one [BusinessEventGenerator] per worker, each
 * over its own [ValueSourceFactory] and its own slice of the key space.
 *
 * A generator per worker rather than one shared between them, because the value sources carry
 * mutable cursors — [SequenceValueSource] in particular — and sharing one would either hand two
 * threads the same primary key or put a contended atomic in front of every generated row. Giving
 * each worker a [workerStripe] makes their key spaces disjoint by construction, so the hot path
 * needs no coordination at all.
 *
 * Also owns the inverse: folding the workers' separate cursors back into the single
 * `state.yaml` entry a resumed run reads — see [snapshotSequences].
 */
class WorkerGenerators(
    data: DataConfig,
    rootSchemaName: String,
    yamlDataRoot: Path,
    seed: Long,
    loadedState: GeneratorState?,
    processStripe: PartitionStripe?,
    concurrency: Int,
) {

    init {
        require(concurrency >= 1) {
            "concurrency must be at least 1; received $concurrency. Set 'concurrency' on the " +
                "scenario in ops.yaml to the number of worker threads this process should run."
        }
    }

    private val factories: List<ValueSourceFactory> = (0 until concurrency).map { worker ->
        ValueSourceFactory(
            yamlDataRoot = yamlDataRoot,
            seed = seed,
            loadedState = loadedState,
            partitionStripe = workerStripe(processStripe, workerIndex = worker, concurrency = concurrency),
        )
    }

    /** One per worker thread, in worker order. */
    val generators: List<BusinessEventGenerator> = factories.map { factory ->
        BusinessEventGenerator(
            data = data,
            rootSchemaName = rootSchemaName,
            factory = factory,
            // A Faker each: Faker is not documented thread-safe, and sharing one would put every
            // worker through the same instance on every generated row.
            faker = Faker(),
            cohortSeed = seed,
        )
    }

    /**
     * The workers' sequence cursors merged into one entry per column, taking the **furthest**
     * cursor any worker reached.
     *
     * Max is the correct fold, not an approximation. Every value a worker has emitted is strictly
     * below that worker's own cursor, so the largest cursor is strictly above every value the run
     * emitted from any worker. Restoring from it therefore cannot re-issue a used key, even
     * though the workers stopped at different points — a resumed worker starts at
     * `max + workerId * step`, which is at or above the maximum. The unused values below it are
     * skipped, and a gap in a synthetic key sequence costs nothing.
     *
     * Taking the minimum, or any one worker's cursor, would re-issue keys the faster workers had
     * already written.
     */
    fun snapshotSequences(): List<SequenceState> = factories
        .flatMap { it.snapshotSequences() }
        .groupBy { it.schemaName to it.columnName }
        .map { (key, states) -> SequenceState(key.first, key.second, states.maxOf { it.nextValue }) }
        .sortedWith(compareBy({ it.schemaName }, { it.columnName }))
}
