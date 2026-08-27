package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.coordinator.Coordinator
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.PartitionStripe
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.target.InMemoryTarget
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * `--instance-index` / `--instance-count`: the flags that let several generator processes on one
 * machine divide a `sequence` key space between them.
 *
 * The assertion that earns its keep is [striped processes write disjoint keys whose union is the
 * unstriped run]. Without a stripe every process starts its sequence at `start` and they write the
 * *same* keys concurrently — which fails silently: throughput collapses and latency climbs with **zero
 * errors** and idle CPU on both sides, because the load is contention on a handful of entries rather
 * than work. Measured on real hardware at 32 processes: 93 ops/s, 498 ms, 383 MB of data from 50M+
 * operations. Nothing in a run's own output says that happened, so it has to be pinned here.
 */
@EnableKubernetesMockClient(crud = true)
class InstanceStripeRunTest {

    /**
     * Injected by [EnableKubernetesMockClient]. Needed only to construct a [Coordinator] — the
     * stripe it hands out is derived from the instance id and touches no API server.
     */
    private lateinit var client: KubernetesClient

    // --- What the flags do to the keys that get written ---

    @Test
    fun `striped processes write disjoint keys whose union is the unstriped run`(@TempDir dir: Path) {
        // Two processes of two, each running the 10-op scenario, against their own targets and their
        // own output directories (state.yaml is per output directory; sharing one would let the second
        // resume the first's cursor and hide the very collision under test).
        val first = runInstance(dir, "i0", instanceIndex = 0, instanceCount = 2)
        val second = runInstance(dir, "i1", instanceIndex = 1, instanceCount = 2)

        assertThat(first).hasSize(10)
        assertThat(second).hasSize(10)
        assertThat(first).doesNotContainAnyElementsOf(second)

        // …and nothing is lost: the union is exactly what one process would have emitted over the
        // same total number of operations. Striping partitions the key space, it does not skip it.
        val unstriped = runInstance(dir, "whole", instanceIndex = null, instanceCount = null, opCount = 20)
        assertThat(first + second).containsExactlyInAnyOrderElementsOf(unstriped)
    }

    @Test
    fun `absent flags reproduce the sequence a run produced before they existed`(@TempDir dir: Path) {
        // Regression guard on "existing behavior": no stripe means start..start+n-1, unchanged.
        val keys = runInstance(dir, "plain", instanceIndex = null, instanceCount = null)

        assertThat(keys).containsExactlyElementsOf((1L..10L).toList())
    }

    @Test
    fun `two processes given the same index write the same keys — the collision, demonstrated`(
        @TempDir dir: Path,
    ) {
        // The flags divide the key space; they cannot check that a fleet used each index once. This is
        // what the failure looks like from the data's side, and why the index must be unique per process.
        val a = runInstance(dir, "dup-a", instanceIndex = 0, instanceCount = 2)
        val b = runInstance(dir, "dup-b", instanceIndex = 0, instanceCount = 2)

        assertThat(a).isEqualTo(b)
    }

    // --- Precedence: a Coordinator stripe and CLI flags are two sources of truth ---

    @Test
    fun `a coordinator stripe plus instance flags is refused, naming both flags`() {
        assertThatThrownBy {
            ScenarioRunnerCli.resolvePartitionStripe(
                coordinatorStripe = PartitionStripe(partitionId = 2, partitionCount = 12),
                cliStripe = PartitionStripe(partitionId = 0, partitionCount = 4),
            )
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
            .hasMessageContaining("--instance-count")
            .hasMessageContaining("distributed mode")
    }

    @Test
    fun `a coordinator stripe alone is used`() {
        val stripe = ScenarioRunnerCli.resolvePartitionStripe(
            coordinatorStripe = PartitionStripe(partitionId = 2, partitionCount = 12),
            cliStripe = null,
        )

        assertThat(stripe).isEqualTo(PartitionStripe(2, 12))
    }

    @Test
    fun `a CLI stripe alone is used`() {
        val stripe = ScenarioRunnerCli.resolvePartitionStripe(
            coordinatorStripe = null,
            cliStripe = PartitionStripe(partitionId = 1, partitionCount = 4),
        )

        assertThat(stripe).isEqualTo(PartitionStripe(1, 4))
    }

    @Test
    fun `neither leaves the process owning the whole key space`() {
        assertThat(ScenarioRunnerCli.resolvePartitionStripe(null, null)).isNull()
    }

    @Test
    fun `resolve refuses the combination before any load is generated`(@TempDir dir: Path) {
        // Through the real entry point, so the rejection cannot be bypassed by the wiring: resolve()
        // is what every *Main calls before it builds a target or writes a row.
        val args = argsFor(dir, "reject", instanceIndex = 0, instanceCount = 4)
        val coordinatorFactory = ScenarioRunnerCli.CoordinatorFactory { _, _, _ ->
            Coordinator(
                client = client, namespace = "default", scenarioName = "writes",
                instanceId = "pod-1", partitionCount = 12,
            )
        }

        assertThatThrownBy {
            ScenarioRunnerCli.resolve(args, silentLogger(), coordinatorFactory)
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--instance-index")
    }

    // --- Helpers ---

    /** Runs the fixture scenario once and returns the root-schema key of every write, in order. */
    private fun runInstance(
        dir: Path,
        label: String,
        instanceIndex: Int?,
        instanceCount: Int?,
        opCount: Int = 10,
    ): List<Long> {
        val args = argsFor(dir, label, instanceIndex, instanceCount, opCount)
        val logger = silentLogger()
        val target = InMemoryTarget()
        ScenarioRunnerCli.run(args, ScenarioRunnerCli.resolve(args, logger), target, logger)
        return target.writes.map { it.parentRow["id"] as Long }
    }

    /**
     * Writes a self-contained workspace under `dir/<label>` and parses real argv, so the assertions
     * above cover the whole path from the command line to the emitted key — not just the stripe object.
     */
    private fun argsFor(
        dir: Path,
        label: String,
        instanceIndex: Int?,
        instanceCount: Int?,
        opCount: Int = 10,
    ): CliArgs {
        val root = Files.createDirectories(dir.resolve(label))
        val outputDir = Files.createDirectories(root.resolve("output"))

        val dataFile = root.resolve("data.yaml")
        Files.writeString(dataFile, """
            schema_version: 2
            schemas:
              - name: customer
                update_ratio: 0.0
                columns:
                  - { name: id, key: true, affinity: false, null_rate: 0.0,
                      value_source: { kind: sequence, start: 1, step: 1 } }
        """.trimIndent())

        val opsFile = root.resolve("ops.yaml")
        Files.writeString(opsFile, """
            schema_version: 2
            targets:
              - { kind: gg8-kv, name: t1, cluster_name: unused-cluster }
            scenarios:
              - name: writes
                target: t1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 10000.0 }
                duration: { kind: count, value: $opCount }
                read_ratio: 0.0
        """.trimIndent())

        val endpoints = root.resolve("client-endpoints.yaml")
        Files.writeString(endpoints, "clusters: []\n")

        val argv = mutableListOf(
            "--data", dataFile.toString(),
            "--ops", opsFile.toString(),
            "--scenario", "writes",
            "--cluster-endpoints", endpoints.toString(),
            "--output", outputDir.toString(),
            "--run-group", "test-group",
            "--target-cluster", "test-cluster",
        )
        if (instanceIndex != null) { argv += listOf("--instance-index", instanceIndex.toString()) }
        if (instanceCount != null) { argv += listOf("--instance-count", instanceCount.toString()) }
        return parseArgs(argv.toTypedArray())
    }

    private fun silentLogger(): DataGenLogger = object : DataGenLogger {
        override fun lifecycle(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun error(message: String, throwable: Throwable?) {}
        override fun debug(message: String) {}
    }
}
