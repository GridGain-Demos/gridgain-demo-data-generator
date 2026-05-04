package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.CorruptedStateException
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerCliVersionMismatchTest {

    @Test fun `pre-existing state with future schemaVersion fails the run with remediation`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output")
        Files.createDirectories(outputDir.resolve("data-generator/state"))
        // Pre-write a state.yaml with a future version.
        val stateFile = outputDir.resolve("data-generator/state/state.yaml")
        StatePersister().save(
            GeneratorState(
                schemaVersion = 999,
                sequences = emptyList(),
                keys = emptyList(),
                runHistory = emptyList(),
            ),
            stateFile,
        )

        val args = writeFixturesAndBuildArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution = ScenarioRunnerCli.resolve(args, logger)
        val target = InMemoryTarget()

        assertThatThrownBy { ScenarioRunnerCli.run(args, resolution, target, logger) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining("schema_version=999")
            .hasMessageContaining("expects 1")
            .hasMessageContaining("Tear down")
            .hasMessageContaining("does not support migration")
            .hasMessageContaining("matches the plugin's `deployment.yaml` rule")
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
