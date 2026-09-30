package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.BoundedKeySpaceSpec
import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.CountWarmupSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.KeyDistribution
import com.gridgain.demo.datagen.config.NoWarmupSpec
import com.gridgain.demo.datagen.config.OperationMix
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UnboundedKeySpaceSpec
import com.gridgain.demo.datagen.config.WarmupSpec
import com.gridgain.demo.datagen.config.KeySpaceSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import com.gridgain.demo.datagen.target.ReadCall
import com.gridgain.demo.datagen.target.ReadOutcome
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.test.Test

/**
 * The three capabilities ops v10 adds: a warmup window, a bounded key space, and a weighted
 * operation mix including `put_get`.
 *
 * Between them they are what separates a data generator from something that can answer a
 * benchmark's question — a repeatable key domain, a measurement that excludes the cold start, and
 * the read-modify-write operation `read_ratio` could not express.
 */
class ScenarioRunnerV10CapabilitiesTest {

    private fun data(updateRatio: Double = 0.0, keySource: SequenceSpec = SequenceSpec(1, 1)) =
        DataConfig(2, listOf(
            SchemaSpec("customer", updateRatio, listOf(
                ColumnSpec("id", 0.0, key = true, valueSource = keySource)
            ))
        ))

    /** Records the order and kind of every call, which is how `put_get` is identified. */
    private class RecordingTarget : Target {
        override val supportsReads = true
        override val supportsTransactions = false
        val calls = mutableListOf<Pair<String, Any>>()      // "read"/"write" to key
        val reads = mutableListOf<ReadCall>()
        val writes = mutableListOf<BusinessEvent>()

        override fun write(event: BusinessEvent): WriteOutcome {
            writes.add(event)
            calls.add("write" to (event.parentRow["id"] ?: "?"))
            return WriteOutcome(success = true)
        }

        override fun read(cacheName: String, key: Any): ReadOutcome {
            reads.add(ReadCall(cacheName, key))
            calls.add("read" to key)
            return ReadOutcome(success = true, value = "found")
        }
    }

    private fun scenario(
        name: String,
        count: Long,
        operations: OperationMix,
        warmup: WarmupSpec = NoWarmupSpec(),
        keySpace: KeySpaceSpec = UnboundedKeySpaceSpec(),
    ) = ScenarioSpec(
        name = name,
        rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(opsPerSecond = 100_000.0),
        duration = CountDurationSpec(count),
        transactionScope = TransactionScope.NONE,
        concurrency = 1,
        operations = operations,
        warmup = warmup,
        keySpace = keySpace,
    )

    private fun runner(
        dir: Path, scenario: ScenarioSpec, target: Target, data: DataConfig = data(), seed: Long = 1L,
    ): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario, data, listOf(gen), target, decisionRandom = Random(seed))
    }

    // -----------------------------------------------------------------------
    // put_get
    // -----------------------------------------------------------------------

    /**
     * `put_get` reads and writes **the same key**, as one operation.
     *
     * This is the whole reason a weight map replaced `read_ratio`. Interleaving independent gets and
     * puts — all the old scalar could do — is a different workload: it never touches one partition
     * twice in succession and never performs the read-modify-write a real application does.
     */
    @Test
    fun `put_get reads and writes the same key as a single operation`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("pg", 40, OperationMix(put = 0.0, get = 0.0, putGet = 1.0),
            keySpace = BoundedKeySpaceSpec(size = 50, distribution = KeyDistribution.UNIFORM))
        val result = runner(dir, spec, target).run()

        assertThat(result.successCount).isEqualTo(40)
        assertThat(target.reads).hasSize(40)
        assertThat(target.writes).hasSize(40)

        // Every read must be immediately followed by a write to the very same key.
        assertThat(target.calls).hasSize(80)
        target.calls.chunked(2).forEach { (first, second) ->
            assertThat(first.first).isEqualTo("read")
            assertThat(second.first).isEqualTo("write")
            assertThat(second.second)
                .describedAs("the put half must target the key the get half just read")
                .isEqualTo(first.second)
        }
    }

    @Test
    fun `an operation mix of only puts performs no reads`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("p", 30, OperationMix(put = 1.0, get = 0.0, putGet = 0.0))
        runner(dir, spec, target).run()

        assertThat(target.writes).hasSize(30)
        assertThat(target.reads).isEmpty()
    }

    @Test
    fun `operation weights must sum to one`() {
        assertThatThrownBy { OperationMix(put = 0.5, get = 0.2, putGet = 0.0) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("must sum to 1.0")
    }

    // -----------------------------------------------------------------------
    // Bounded key space
    // -----------------------------------------------------------------------

    /**
     * Bounded mode makes reads hit, which is the point of having it.
     *
     * In unbounded mode a get can only find a row this process wrote during this run, so a
     * read-heavy scenario against a cold registry misses constantly and still reports a healthy
     * rate. A bounded space that both halves draw from removes the possibility.
     */
    @Test
    fun `a bounded key space keeps every key inside the range`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("bounded", 300, OperationMix(put = 0.5, get = 0.5, putGet = 0.0),
            keySpace = BoundedKeySpaceSpec(size = 20, distribution = KeyDistribution.UNIFORM))
        runner(dir, spec, target).run()

        // Sequence starts at 1 with step 1, so index i maps to key 1 + i, giving 1..20.
        val keysTouched = (target.writes.map { it.parentRow["id"] } + target.reads.map { it.key })
            .map { (it as Number).toLong() }
        assertThat(keysTouched).isNotEmpty()
        assertThat(keysTouched.min()).isGreaterThanOrEqualTo(1L)
        assertThat(keysTouched.max())
            .describedAs("a key outside the bounded space can never be read back")
            .isLessThanOrEqualTo(20L)
        assertThat(keysTouched.toSet().size)
            .describedAs("300 operations over 20 keys must revisit keys — that is the point")
            .isLessThanOrEqualTo(20)
    }

    @Test
    fun `an unbounded key space keeps the pre-v10 growing sequence`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("unbounded", 50, OperationMix(put = 1.0, get = 0.0, putGet = 0.0))
        runner(dir, spec, target).run()

        assertThat(target.writes.map { it.parentRow["id"] }.toSet())
            .describedAs("unbounded mode must still emit 50 distinct keys from the sequence")
            .hasSize(50)
    }

    /**
     * A bounded key space needs a key it can compute, and only a `sequence` gives one.
     *
     * Refused explicitly rather than fudged. Silently ignoring the bound, or inventing a mapping
     * from an index onto a DataFaker name, would produce a run that looks bounded and is not — and
     * every figure from it would be wrong in a way nothing reports.
     */
    @Test
    fun `a bounded key space rejects a key column it cannot index`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val nonSequenceKey = DataConfig(2, listOf(
            SchemaSpec("customer", 0.0, listOf(
                ColumnSpec("id", 0.0, key = true,
                    valueSource = com.gridgain.demo.datagen.config.UniqueSpec("#{internet.username}"))
            ))
        ))
        val spec = scenario("bad", 10, OperationMix(put = 1.0, get = 0.0, putGet = 0.0),
            keySpace = BoundedKeySpaceSpec(size = 10, distribution = KeyDistribution.UNIFORM))

        assertThatThrownBy { runner(dir, spec, target, nonSequenceKey) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("key_space")
            .hasMessageContaining("sequence")
    }

    // -----------------------------------------------------------------------
    // Fan-out: what one "operation" actually costs the cluster
    // -----------------------------------------------------------------------

    /**
     * One operation is one **business event**, which may be many row writes across several caches.
     *
     * Nothing said so. `achieved_rate` counts events, so a data.yaml whose root schema has children
     * reports a number that looks like puts/sec and is not — the same run against a single-schema
     * file would report a far higher figure for identical cluster work. Reporting the measured
     * fan-out makes the two comparable instead of silently incomparable.
     *
     * A single-schema data.yaml — no `parent-fk-ref` pointing at the root — already gives exactly
     * one put per operation. That is what makes a benchmark-shaped data.yaml possible without a new
     * knob; what was missing was any way to know you had one.
     */
    @Test
    fun `a single-schema data file writes exactly one row per operation`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("single", 25, OperationMix(put = 1.0, get = 0.0, putGet = 0.0))
        val result = runner(dir, spec, target).run()

        assertThat(result.rowsWritten).isEqualTo(25)
        assertThat(result.rowsPerWrite)
            .describedAs("a benchmark-shaped data.yaml must report a fan-out of exactly 1.0")
            .isEqualTo(1.0)
    }

    @Test
    fun `a data file with children reports the fan-out it actually wrote`(@TempDir dir: Path) {
        val withChildren = DataConfig(2, listOf(
            SchemaSpec("customer", 0.0, listOf(
                ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
            )),
            SchemaSpec("order", 0.0, listOf(
                ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1)),
                ColumnSpec("customer_id", 0.0, valueSource = com.gridgain.demo.datagen.config.ParentFkRefSpec(
                    parentSchema = "customer", parentColumn = "id",
                    cohortBuckets = listOf(com.gridgain.demo.datagen.config.CohortBucket(1.0, 3)),
                )),
            )),
        ))
        val target = RecordingTarget()
        val spec = scenario("fanout", 20, OperationMix(put = 1.0, get = 0.0, putGet = 0.0))
        val result = runner(dir, spec, target, withChildren).run()

        assertThat(result.rowsPerWrite)
            .describedAs(
                "each event wrote a parent plus 3 orders, so a throughput figure counting events " +
                    "understates the cluster's row rate fourfold"
            )
            .isEqualTo(4.0)
        assertThat(result.rowsWritten).isEqualTo(80)
    }

    // -----------------------------------------------------------------------
    // Warmup
    // -----------------------------------------------------------------------

    /**
     * Warmup operations are performed but not measured.
     *
     * They still run, still hit the target and still populate the key space — a benchmark that skips
     * them measures a cluster in a state no application ever sees. What they do not do is enter the
     * latency histogram or the achieved rate.
     */
    @Test
    fun `warmup operations execute but stay out of the measurement`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("warm", 100, OperationMix(put = 1.0, get = 0.0, putGet = 0.0),
            warmup = CountWarmupSpec(60))
        val result = runner(dir, spec, target).run()

        assertThat(target.writes)
            .describedAs("all 100 operations must actually run; warmup is not a discount")
            .hasSize(100)
        assertThat(result.successCount).isEqualTo(100)
        assertThat(result.measuredOperations)
            .describedAs("60 of the 100 were warmup, so 40 were measured")
            .isEqualTo(40)
    }

    @Test
    fun `with no warmup every operation is measured`(@TempDir dir: Path) {
        val target = RecordingTarget()
        val spec = scenario("nowarm", 50, OperationMix(put = 1.0, get = 0.0, putGet = 0.0))
        val result = runner(dir, spec, target).run()

        assertThat(result.measuredOperations).isEqualTo(50)
        assertThat(result.successCount).isEqualTo(50)
    }

    /**
     * The reported latency describes the measured window only.
     *
     * Asserted through a target whose warmup operations are deliberately slow: if the warmup
     * latencies were still in the histogram, the reported maximum would carry the slow ones.
     */
    @Test
    fun `reported latency excludes the warmup operations`(@TempDir dir: Path) {
        val slowFirst = object : Target {
            override val supportsReads = true
            override val supportsTransactions = false
            var count = 0
            override fun write(event: BusinessEvent): WriteOutcome {
                if (count++ < 20) Thread.sleep(6)          // the "cold start"
                return WriteOutcome(success = true)
            }
            override fun read(cacheName: String, key: Any) = ReadOutcome(true, "x")
        }
        val spec = scenario("warmlat", 60, OperationMix(put = 1.0, get = 0.0, putGet = 0.0),
            warmup = CountWarmupSpec(20))
        val result = runner(dir, spec, slowFirst).run()

        assertThat(result.measuredOperations).isEqualTo(40)
        assertThat(result.latency.maxMs)
            .describedAs(
                "the 6ms warmup writes must not appear in the measured histogram; max was %.2fms",
                result.latency.maxMs,
            )
            .isLessThan(5.0)
    }
}
