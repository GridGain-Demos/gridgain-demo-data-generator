package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerCliStateTest {

    @Test fun `state survives across two runs against an InMemoryTarget`(@TempDir dir: Path) {
        val outputDir = dir.resolve("output")
        Files.createDirectories(outputDir)
        val args = writeFixturesAndBuildArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")

        // Run 1.
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution1 = ScenarioRunnerCli.resolve(args, logger)
        val target1 = InMemoryTarget()
        val r1 = ScenarioRunnerCli.run(args, resolution1, target1, logger)
        assertThat(r1.successCount).isEqualTo(10L)

        val stateFile = outputDir.resolve("data-generator/state/state.yaml")
        val state1 = StatePersister().load(stateFile)!!
        assertThat(state1.sequences.first { it.schemaName == "customer" }.nextValue).isEqualTo(11L)
        assertThat(state1.keys.first { it.schemaName == "customer" }.keys).hasSize(10)
        assertThat(state1.runHistory).hasSize(1)

        // Run 2 — fresh CLI invocation, same outputDir.
        val resolution2 = ScenarioRunnerCli.resolve(args, logger)
        val target2 = InMemoryTarget()
        val r2 = ScenarioRunnerCli.run(args, resolution2, target2, logger)
        assertThat(r2.successCount).isEqualTo(10L)

        val state2 = StatePersister().load(stateFile)!!
        // Sequence continued: 11 -> 21.
        assertThat(state2.sequences.first { it.schemaName == "customer" }.nextValue).isEqualTo(21L)
        // Keys append, no resets.
        assertThat(state2.keys.first { it.schemaName == "customer" }.keys).hasSize(20)
        // Run history grew.
        assertThat(state2.runHistory).hasSize(2)
        assertThat(state2.runHistory.map { it.scenarioName }).containsExactly("writes", "writes")
    }

    private fun writeFixturesAndBuildArgs(dir: Path, outputDir: Path, scenarioName: String): CliArgs {
        val dataFile = dir.resolve("data.yaml")
        Files.writeString(dataFile, """
            schema_version: 2
            schemas:
              - name: customer
                update_ratio: 0.0
                columns:
                  - { name: id, key: true, affinity: false, null_rate: 0.0,
                      value_source: { kind: sequence, start: 1, step: 1 } }
        """.trimIndent())

        val opsFile = dir.resolve("ops.yaml")
        Files.writeString(opsFile, """
            schema_version: 2
            targets:
              - { kind: gg8-kv, name: t1, cluster_name: unused-cluster }
            scenarios:
              - name: $scenarioName
                target: t1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 100.0 }
                duration: { kind: count, value: 10 }
                read_ratio: 0.0
        """.trimIndent())

        // Needed by ScenarioRunnerCli.resolve which sets the gg.demo.client.endpoints sysprop;
        // InMemoryTarget never reads it, but the file path must exist for toAbsolutePath to work.
        val endpoints = dir.resolve("client-endpoints.yaml")
        Files.writeString(endpoints, "clusters: []\n")

        return CliArgs(
            dataFile = dataFile,
            opsFile = opsFile,
            scenarioName = scenarioName,
            clusterEndpoints = endpoints,
            outputDir = outputDir,
        )
    }
}
