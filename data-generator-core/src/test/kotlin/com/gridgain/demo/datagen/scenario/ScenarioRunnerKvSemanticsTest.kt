package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
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

    private fun runner(dir: Path, scenario: ScenarioSpec, target: InMemoryTarget, data: DataConfig,
                       decisionSeed: Long = 1L): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(data, "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario, data, gen, target, decisionRandom = Random(decisionSeed))
    }

    @Test
    fun `read_ratio of zero produces only writes`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "writes-only", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(1000.0), duration = CountDurationSpec(50),
            transactionScope = TransactionScope.NONE, readRatio = 0.0,
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes).hasSize(50)
        assertThat(target.reads).isEmpty()
    }

    @Test
    fun `read_ratio of half produces a mix once registry warms up`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "mix", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(200),
            transactionScope = TransactionScope.NONE, readRatio = 0.5,
        )
        runner(dir, scenario, target, data()).run()
        assertThat(target.writes.size + target.reads.size).isEqualTo(200)
        assertThat(target.writes).isNotEmpty()
        assertThat(target.reads).isNotEmpty()
    }

    @Test
    fun `update_ratio reuses previously registered keys`(@TempDir dir: Path) {
        val target = InMemoryTarget()
        val scenario = ScenarioSpec(
            name = "update-heavy", rootSchemas = listOf("customer"),
            rate = ConstantRateSpec(2000.0), duration = CountDurationSpec(100),
            transactionScope = TransactionScope.NONE, readRatio = 0.0,
        )
        runner(dir, scenario, target, data(updateRatio = 0.8)).run()
        // Without update_ratio, the sequence value source would emit 100 distinct keys (1..100).
        // With update_ratio=0.8 and ~80% of writes substituted with existing keys, distinct count is far less.
        val distinct = target.writes.map { it.parentRow["id"] }.toSet()
        assertThat(distinct.size).isLessThan(60)
    }
}
