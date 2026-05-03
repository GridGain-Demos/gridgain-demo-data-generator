package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class RowGeneratorTest {

    @Test
    fun `produces a row with declared columns in declared order`(@TempDir dir: Path) {
        val schema = SchemaSpec(
            name = "customer",
            updateRatio = 0.0,
            columns = listOf(
                ColumnSpec("id", 0.0, SequenceSpec(start = 1, step = 1)),
                ColumnSpec("name", 0.0, DataFakerSpec("#{name.firstName}")),
            ),
        )
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = RowGenerator(schema, factory, faker = Faker())

        val row = gen.next()
        assertThat(row.keys.toList()).containsExactly("id", "name")
        assertThat(row["id"]).isEqualTo(1L)
        assertThat(row["name"]).isInstanceOf(String::class.java)

        assertThat(gen.next()["id"]).isEqualTo(2L)
    }

    @Test
    fun `null_rate of one yields null in the corresponding column`(@TempDir dir: Path) {
        val schema = SchemaSpec(
            name = "s",
            updateRatio = 0.0,
            columns = listOf(
                ColumnSpec("forced_null", 1.0, SequenceSpec(start = 1, step = 1)),
            ),
        )
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = RowGenerator(schema, factory, faker = Faker())
        repeat(20) { assertThat(gen.next()["forced_null"]).isNull() }
    }
}
