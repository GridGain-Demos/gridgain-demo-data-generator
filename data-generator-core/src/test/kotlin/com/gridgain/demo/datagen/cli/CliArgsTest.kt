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
        val without = withoutFlag(complete, "--target-cluster")

        assertThatThrownBy { parseArgs(without) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("flag '--target-cluster' is missing or empty")
            .hasMessageContaining("Missing flags: --target-cluster")
            .hasMessageContaining("Expected invocation")
    }

    @Test
    fun `a blank target cluster is rejected like a missing one`() {
        val blank = withBlankValue(complete, "--target-cluster")

        assertThatThrownBy { parseArgs(blank) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("flag '--target-cluster' is missing or empty")
    }

    /** Drops [flag] and its value wherever it sits in [args], instead of assuming a position. */
    private fun withoutFlag(args: Array<String>, flag: String): Array<String> {
        val list = args.toList()
        val idx = list.indexOf(flag)
        check(idx >= 0) { "fixture must contain $flag" }
        return (list.subList(0, idx) + list.subList(idx + 2, list.size)).toTypedArray()
    }

    /** Blanks [flag]'s value wherever it sits in [args], instead of assuming a position. */
    private fun withBlankValue(args: Array<String>, flag: String): Array<String> {
        val list = args.toMutableList()
        val idx = list.indexOf(flag)
        check(idx >= 0) { "fixture must contain $flag" }
        list[idx + 1] = "  "
        return list.toTypedArray()
    }
}
