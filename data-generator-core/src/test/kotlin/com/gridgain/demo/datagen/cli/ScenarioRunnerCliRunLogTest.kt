package com.gridgain.demo.datagen.cli

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList
import kotlin.test.Test

class ScenarioRunnerCliRunLogTest {

    @Test fun `run writes a multi-doc run log with started, stopped, and state-persisted`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output").also { Files.createDirectories(it) }
        val args = buildCliArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution = ScenarioRunnerCli.resolve(args, logger)
        val target = InMemoryTarget(supportsReads = true, supportsTransactions = false)
        ScenarioRunnerCli.run(args, resolution, target, logger)

        val runDirs = Files.list(outputDir.resolve("data-generator/runs")).use { it.toList() }
        assertThat(runDirs).hasSize(1)
        val runLog = runDirs.first().resolve("run.log.yaml")
        assertThat(Files.exists(runLog)).isTrue()

        val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper
        val docs = mapper.readValues(
            mapper.factory.createParser(runLog.toFile()), Map::class.java,
        ).readAll().filterIsInstance<Map<String, Any>>()
        val events = docs.map { it["event"] as String }
        assertThat(events).containsSubsequence(
            "scenario.started", "scenario.stopped", "state.persisted",
        )
    }

    private fun buildCliArgs(dir: Path, outputDir: Path, scenarioName: String): CliArgs {
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
            runGroup = "test-group",
        )
    }
}
