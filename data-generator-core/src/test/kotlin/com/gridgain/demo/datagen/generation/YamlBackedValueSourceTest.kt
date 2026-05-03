package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.writeText
import kotlin.test.Test

class YamlBackedValueSourceTest {

    private val ctx = GenerationContext(Faker())

    @Test
    fun `picks values from the named list`(@TempDir dir: Path) {
        val file = dir.resolve("colors.yaml").also {
            it.writeText("colors:\n  - red\n  - green\n  - blue\n")
        }
        val s = YamlBackedValueSource(file, key = "colors", random = Random(1L))
        val seen = (1..100).map { s.next(ctx) as String }.toSet()
        assertThat(seen).isSubsetOf("red", "green", "blue")
        assertThat(seen).hasSizeGreaterThan(1)
    }

    @Test
    fun `missing key produces remediation`(@TempDir dir: Path) {
        val file = dir.resolve("colors.yaml").also { it.writeText("colors:\n  - red\n") }
        val s = YamlBackedValueSource(file, key = "names", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("names")
            .hasMessageContaining(file.toString())
    }

    @Test
    fun `key whose value is not a list is rejected`(@TempDir dir: Path) {
        val file = dir.resolve("data.yaml").also { it.writeText("count: 5\n") }
        val s = YamlBackedValueSource(file, key = "count", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("count")
            .hasMessageContaining("list")
    }

    @Test
    fun `missing file produces remediation`(@TempDir dir: Path) {
        val s = YamlBackedValueSource(dir.resolve("absent.yaml"), key = "x", random = Random(1L))
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("absent.yaml")
            .hasMessageContaining("does not exist")
    }
}
