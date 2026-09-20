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

    // --- Instance striping (--instance-index / --instance-count) ---
    //
    // Optional, and both-or-neither. Absent means this process owns the whole key space, which is
    // what every host and local run got before these flags existed — and why N of them sharing one
    // data.yaml with a `sequence` source all wrote the same keys.

    @Test
    fun `absent instance flags leave the stripe null, the whole key space`() {
        assertThat(parseArgs(complete).instanceStripe).isNull()
    }

    @Test
    fun `both instance flags produce the stripe they name`() {
        val striped = parseArgs(complete + arrayOf("--instance-index", "1", "--instance-count", "4"))

        assertThat(striped.instanceStripe).isNotNull
        assertThat(striped.instanceStripe!!.partitionId).isEqualTo(1)
        assertThat(striped.instanceStripe!!.partitionCount).isEqualTo(4)
    }

    @Test
    fun `an index without a count is rejected, naming both flags`() {
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-index", "0")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
            .hasMessageContaining("--instance-count")
    }

    @Test
    fun `a count without an index is rejected, naming both flags`() {
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-count", "4")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
            .hasMessageContaining("--instance-count")
    }

    @Test
    fun `an out-of-range index is rejected with the flags, not with an internal require`() {
        // PartitionStripe's own init would say "partitionId 4 out of range [0, 2)" — correct, but it
        // names fields the operator never typed and reads as a generator bug.
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-index", "4", "--instance-count", "2")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
            .hasMessageContaining("--instance-count")
            .hasMessageContaining("0..1")
    }

    @Test
    fun `a negative index is rejected`() {
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-index", "-1", "--instance-count", "2")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
    }

    @Test
    fun `a zero count is rejected`() {
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-index", "0", "--instance-count", "0")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-count")
    }

    @Test
    fun `a non-integer count is rejected, naming the flag`() {
        assertThatThrownBy { parseArgs(complete + arrayOf("--instance-index", "0", "--instance-count", "four")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-count")
    }

    @Test
    fun `an index of zero with a count of one is the whole key space, stated explicitly`() {
        // Mathematically identical to no stripe (stride 1*step, offset 0). Accepted rather than
        // refused: it is what a launcher that always passes the pair emits for a single process.
        val striped = parseArgs(complete + arrayOf("--instance-index", "0", "--instance-count", "1"))

        assertThat(striped.instanceStripe!!.partitionId).isEqualTo(0)
        assertThat(striped.instanceStripe!!.partitionCount).isEqualTo(1)
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

/**
 * `--instance-id` exists to collapse two ids that were never connected.
 *
 * The toolkit names a generator process — its systemd unit instance, its run directory, its
 * run.log — and the process then minted an unrelated `RunId` of its own, which is what reached the
 * metrics feed and therefore the demo UI. So the instance a user saw running slowly on the Load
 * page could not be found on a machine, in a log, or in Grafana: three different names for one
 * process, with no mapping between them anywhere.
 *
 * Optional, because a standalone run has no launcher to supply one and `RunId.generate()` remains
 * the right answer there.
 */
class InstanceIdArgTest {

    private fun baseArgs(vararg extra: String) = arrayOf(
        "--data", "d.yaml", "--ops", "o.yaml", "--scenario", "s",
        "--cluster-endpoints", "e.yaml", "--output", "out",
        "--run-group", "grp", "--target-cluster", "c",
        *extra,
    )

    @kotlin.test.Test
    fun `an instance id is carried when supplied`() {
        val parsed = parseArgs(baseArgs("--instance-id", "run-42-i3"))

        kotlin.test.assertEquals("run-42-i3", parsed.instanceId)
    }

    @kotlin.test.Test
    fun `it is absent by default, leaving the process to mint its own`() {
        kotlin.test.assertNull(parseArgs(baseArgs()).instanceId)
    }

    @kotlin.test.Test
    fun `a blank value is treated as absent rather than as an empty identity`() {
        // systemd renders an unset variable as an empty word; an id of "" would key a live
        // instance under the empty string and collide with every other such process.
        kotlin.test.assertNull(parseArgs(baseArgs("--instance-id", "  ")).instanceId)
    }

    @kotlin.test.Test
    fun `the flag is listed in the invocation help, so a missing required flag names it too`() {
        val message = kotlin.test.assertFailsWith<com.gridgain.demo.datagen.errors.MisconfigurationException> {
            parseArgs(arrayOf("--data", "d.yaml"))
        }.message!!

        kotlin.test.assertTrue(
            message.contains("--instance-id"),
            "the optional-flag list is the only place an operator learns this exists; got: $message",
        )
    }
}
