package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class ValueSourceFactoryTest {

    private fun column(spec: com.gridgain.demo.datagen.config.ValueSourceSpec, nullRate: Double = 0.0) =
        ColumnSpec(name = "c", nullRate = nullRate, valueSource = spec)

    @Test
    fun `builds a SequenceValueSource for SequenceSpec`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 5, step = 1)))
        assertThat(vs).isInstanceOf(SequenceValueSource::class.java)
    }

    @Test
    fun `wraps in NullRateApplicator when null_rate is positive`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 1, step = 1), nullRate = 0.1))
        assertThat(vs).isInstanceOf(NullRateApplicator::class.java)
    }

    @Test
    fun `does not wrap when null_rate is zero`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val vs = factory.build(column(SequenceSpec(start = 1, step = 1), nullRate = 0.0))
        assertThat(vs).isNotInstanceOf(NullRateApplicator::class.java)
    }

    @Test
    fun `builds DataFaker, Unique, WeightedChoice, and Yaml-backed sources`(@TempDir dir: Path) {
        dir.resolve("c.yaml").writeText("xs:\n  - a\n  - b\n")
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        assertThat(factory.build(column(DataFakerSpec("#{name.firstName}"))))
            .isInstanceOf(DataFakerValueSource::class.java)
        assertThat(factory.build(column(UniqueSpec("#{name.firstName}"))))
            .isInstanceOf(UniqueValueSource::class.java)
        assertThat(factory.build(column(WeightedChoiceSpec(listOf(WeightedChoice("a", 1.0))))))
            .isInstanceOf(WeightedChoiceValueSource::class.java)
        assertThat(factory.build(column(YamlDataSpec(path = "c.yaml", key = "xs"))))
            .isInstanceOf(YamlBackedValueSource::class.java)
    }

    @Test
    fun `builds ParentFkRef and KeySuffix sources`(@TempDir dir: Path) {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        assertThat(
            factory.build(column(ParentFkRefSpec("customer", "id", listOf(CohortBucket(1.0, 1)))))
        ).isInstanceOf(ParentFkRefValueSource::class.java)
        assertThat(
            factory.build(column(KeySuffixSpec(baseColumn = "id", separator = "-", length = 4)))
        ).isInstanceOf(KeySuffixValueSource::class.java)
    }
}
