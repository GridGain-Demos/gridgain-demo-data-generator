package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.state.GeneratorState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * The per-worker generator set: one [BusinessEventGenerator] per worker thread, each over its own
 * [ValueSourceFactory] and its own slice of the key space, plus the rule for folding their
 * separate sequence cursors back into one `state.yaml`.
 */
class WorkerGeneratorsTest {

    private fun data() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(
            ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(start = 0, step = 1)),
        ))
    ))

    private fun build(
        dir: Path,
        concurrency: Int,
        processStripe: PartitionStripe? = null,
        loadedState: GeneratorState? = null,
    ) = WorkerGenerators(
        data = data(),
        rootSchemaName = "customer",
        yamlDataRoot = dir,
        seed = 0L,
        loadedState = loadedState,
        processStripe = processStripe,
        concurrency = concurrency,
    )

    @Test
    fun `builds one generator per worker`(@TempDir dir: Path) {
        assertThat(build(dir, concurrency = 4).generators).hasSize(4)
    }

    @Test
    fun `workers emit disjoint keys`(@TempDir dir: Path) {
        val workers = build(dir, concurrency = 4)

        val emitted = workers.generators.flatMap { gen ->
            (0 until 25).map { gen.next().parentRow["id"] as Long }
        }

        assertThat(emitted).doesNotHaveDuplicates().hasSize(100)
    }

    @Test
    fun `the snapshot keeps the furthest cursor any worker reached`(@TempDir dir: Path) {
        val workers = build(dir, concurrency = 4)
        // Deliberately uneven, as real workers are: worker 0 runs far ahead of the rest.
        repeat(10) { workers.generators[0].next() }
        repeat(2) { workers.generators[1].next() }

        val snapshot = workers.snapshotSequences()

        assertThat(snapshot).hasSize(1)
        assertThat(snapshot.single().schemaName).isEqualTo("customer")
        assertThat(snapshot.single().columnName).isEqualTo("id")
        assertThat(snapshot.single().nextValue)
            .describedAs(
                "worker 0 emitted 0,4,8..36 so its cursor is 40; the merged cursor must be the " +
                    "furthest any worker reached, since every emitted value is below it"
            )
            .isEqualTo(40L)
    }

    /**
     * The property the merge rule exists for. Restoring from a single merged cursor must never
     * re-issue a key the previous run already wrote, even though the workers stopped at different
     * points and the one that lagged has a much lower cursor of its own.
     */
    @Test
    fun `a resumed run never re-issues a key the previous run emitted`(@TempDir dir: Path) {
        val first = build(dir, concurrency = 4)
        val firstKeys = buildList {
            repeat(10) { add(first.generators[0].next().parentRow["id"] as Long) }
            repeat(3) { add(first.generators[1].next().parentRow["id"] as Long) }
            repeat(7) { add(first.generators[2].next().parentRow["id"] as Long) }
        }

        val resumed = build(
            dir,
            concurrency = 4,
            loadedState = GeneratorState(2, first.snapshotSequences(), emptyList(), emptyList()),
        )
        val resumedKeys = resumed.generators.flatMap { gen ->
            (0 until 10).map { gen.next().parentRow["id"] as Long }
        }

        assertThat(resumedKeys).doesNotHaveDuplicates()
        assertThat(resumedKeys).doesNotContainAnyElementsOf(firstKeys)
    }

    @Test
    fun `a single worker is unstriped, so its keys are the plain sequence`(@TempDir dir: Path) {
        val workers = build(dir, concurrency = 1)

        val emitted = (0 until 5).map { workers.generators.single().next().parentRow["id"] as Long }

        assertThat(emitted)
            .describedAs("concurrency 1 must produce exactly the keys it did before concurrency existed")
            .containsExactly(0L, 1L, 2L, 3L, 4L)
    }

    @Test
    fun `worker stripes nest inside the process stripe`(@TempDir dir: Path) {
        val processZero = build(dir, concurrency = 2, processStripe = PartitionStripe(0, 2))
        val processOne = build(dir, concurrency = 2, processStripe = PartitionStripe(1, 2))

        val emitted = (processZero.generators + processOne.generators)
            .flatMap { gen -> (0 until 5).map { gen.next().parentRow["id"] as Long } }

        assertThat(emitted)
            .describedAs("two machines x two threads must tile the key space with no overlap")
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf((0L until 20L).toList())
    }

    @Test
    fun `concurrency below one is refused`(@TempDir dir: Path) {
        org.assertj.core.api.Assertions.assertThatThrownBy { build(dir, concurrency = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("concurrency")
    }
}
