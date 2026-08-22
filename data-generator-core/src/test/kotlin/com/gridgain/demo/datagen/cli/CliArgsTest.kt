package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class CliArgsTest {

    private val complete = arrayOf(
        "--data", "/tmp/data.yaml",
        "--ops", "/tmp/ops.yaml",
        "--scenario", "load",
        "--cluster-endpoints", "/tmp/client-endpoints.yaml",
        "--output", "/tmp/out",
        "--run-group", "rg-1",
        "--target-cluster", "prod-gg8",
    )

    @Test
    fun `parses the target cluster`() {
        assertThat(parseArgs(complete).targetCluster).isEqualTo("prod-gg8")
    }

    @Test
    fun `a missing target cluster names the flag and the full invocation`() {
        val without = complete.toList().dropLast(2).toTypedArray()

        assertThatThrownBy { parseArgs(without) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
            .hasMessageContaining("Expected invocation")
    }

    @Test
    fun `a blank target cluster is rejected like a missing one`() {
        val blank = complete.copyOf()
        blank[blank.lastIndex] = "  "

        assertThatThrownBy { parseArgs(blank) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
    }
}
