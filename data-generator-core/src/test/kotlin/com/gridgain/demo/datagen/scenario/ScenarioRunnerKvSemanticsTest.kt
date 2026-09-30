package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.NoWarmupSpec
import com.gridgain.demo.datagen.config.OperationMix
import com.gridgain.demo.datagen.config.UnboundedKeySpaceSpec
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.test.Test

class ScenarioRunnerKvSemanticsTest {

    private fun data(updateRatio: Double = 0.0) = DataConfig(2, listOf(
        SchemaSpec("customer", updateRatio, listOf(
            ColumnSpec("id", 0.0, key = true, valueSource = SequenceSpec(1, 1))
        ))
    ))

    /**
     * Succeeds at every read and finds nothing — the shape a real cluster takes when the key space
     * is wider than what has been written into it.
     */
    private class AlwaysMissingTarget : Target {
        override val supportsReads = true
        override val supportsTransactions = false
        private val _reads = mutableListOf<ReadCall>()
        val reads: List<ReadCall> get() = _reads

        override fun write(event: BusinessEvent) = WriteOutcome(success = true)
        override fun read(cacheName: String, key: Any): ReadOutcome {
            _reads.add(ReadCall(cacheName, key))
            return ReadOutcome(success = true, value = null)
        }
    }

    private fun runner(dir: Path, scenario: ScenarioSpec, target: Target, data: DataConfig,
                       decisionSeed: Long = 1L): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario, data, listOf(gen), target, decisionRandom = Random(decisionSeed))
    }

    /**
     * A read that finds nothing is reported as a miss, not quietly counted as a success.
     *
     * `ReadOutcome` carries both a `success` flag and a `value`, and the runner only ever looked at
     * the flag — so a get against a key that is not there returned `success = true` and the run
     * reported a healthy rate. That is the difference between a get benchmark and a benchmark of
     * how fast a cluster can say "no": a run can be entirely misses and look perfect.
     *
     * Misses stay out of `error_count` on purpose. A miss is not a failure of the cluster or the
     * client, and folding it into errors would make a legitimately sparse key space look broken.
     * It is its own number, next to the errors.
     */
    @Test
    fun `a read that finds nothing is counted as a miss rather than a success`(@TempDir dir: Path) {
        val target = AlwaysMissingTarget()
        val scenario = ScenarioSpec(concurrency = 1,
            name = "all-misses", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(200),
            transactionScope = TransactionScope.NONE, operations = OperationMix(put = 0.5, get = 0.5, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )
        val result = runner(dir, scenario, target, data()).run()

        assertThat(target.reads).isNotEmpty()
        assertThat(result.readMissCount)
            .describedAs("every read missed, so the miss count must equal the number of reads")
            .isEqualTo(target.reads.size.toLong())
        assertThat(result.errorCount)
            .describedAs("a miss is not an error — a sparse key space is not a broken cluster")
            .isEqualTo(0)
    }

    @Test
    fun `reads that find their value record no misses`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(concurrency = 1,
            name = "hits", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(200),
            transactionScope = TransactionScope.NONE, operations = OperationMix(put = 0.5, get = 0.5, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )
        val result = runner(dir, scenario, target, data()).run()

        assertThat(target.reads).isNotEmpty()
        assertThat(result.readMissCount).isEqualTo(0)
    }

    @Test
    fun `read_ratio of zero produces only writes`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(concurrency = 1, 
            name = "writes-only", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1000.0), duration = CountDurationSpec(50),
            transactionScope = TransactionScope.NONE, operations = OperationMix(put = 1.0, get = 0.0, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes).hasSize(50)
        assertThat(target.reads).isEmpty()
    }

    @Test
    fun `read_ratio of half produces a mix once registry warms up`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(concurrency = 1, 
            name = "mix", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(200),
            transactionScope = TransactionScope.NONE, operations = OperationMix(put = 0.5, get = 0.5, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes.size + target.reads.size).isEqualTo(200)
        assertThat(target.writes).isNotEmpty()
        assertThat(target.reads).isNotEmpty()
    }

    @Test
    fun `update_ratio reuses previously registered keys`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(concurrency = 1, 
            name = "update-heavy", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(100),
            transactionScope = TransactionScope.NONE, operations = OperationMix(put = 1.0, get = 0.0, putGet = 0.0), warmup = NoWarmupSpec(), keySpace = UnboundedKeySpaceSpec(),
        )
        runner(dir, scenario, target, data(updateRatio = 0.8)).run()
        // Without update_ratio, the sequence value source would emit 100 distinct keys (1..100).
        // With update_ratio=0.8 and ~80% of writes substituted with existing keys, distinct count is far less.
        val distinct = target.writes.map { it.parentRow["id"] }.toSet()
        assertThat(distinct.size).isLessThan(60)
    }
}
