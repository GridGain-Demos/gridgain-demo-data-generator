package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class DataConfigDeserializationTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    private fun copy(@TempDir dir: Path, resource: String, name: String): Path {
        val target = dir.resolve(name)
        DataConfigDeserializationTest::class.java.classLoader
            .getResourceAsStream(resource).use { input ->
                requireNotNull(input)
                Files.copy(input, target)
            }
        return target
    }

    @Test
    fun `deserializes every value source kind`(@TempDir dir: Path) {
        val data = copy(dir, "data-v2-customer.yaml", "data.yaml")
        val ops = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val parsed = ConfigurationParser(logger = logger)
            .parse(data.toFile(), ops.toFile())

        val schema = parsed.data.schemas.single()
        assertThat(schema.name).isEqualTo("customer")
        assertThat(schema.updateRatio).isEqualTo(0.05)
        assertThat(schema.columns.map { it.name })
            .containsExactly("id", "first_name", "state", "handle", "zip")

        assertThat(schema.columns[0].valueSource).isEqualTo(SequenceSpec(start = 1, step = 1))
        assertThat(schema.columns[1].valueSource).isEqualTo(DataFakerSpec(expression = "#{name.firstName}"))
        val state = schema.columns[2].valueSource as WeightedChoiceSpec
        assertThat(state.choices).containsExactly(
            WeightedChoice(value = "CA", weight = 0.40),
            WeightedChoice(value = "NY", weight = 0.30),
            WeightedChoice(value = "TX", weight = 0.30),
        )
        assertThat(schema.columns[3].valueSource).isEqualTo(UniqueSpec(expression = "#{internet.username}"))
        assertThat(schema.columns[4].valueSource).isEqualTo(YamlDataSpec(path = "data/us-zips.yaml", key = "zip_codes"))
    }
}
