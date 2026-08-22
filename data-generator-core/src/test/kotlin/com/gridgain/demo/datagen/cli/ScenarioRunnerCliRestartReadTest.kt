package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Spec §13 verification step 7 (restart-then-read). Run 1 writes Long-keyed rows; run 2
 * starts from the saved state.yaml with `read_ratio = 1.0` and must sample those keys
 * back as `Long`, not `String("1")`. F11 closure is what makes this test pass.
 */
class ScenarioRunnerCliRestartReadTest {

    @Test fun `restart with read_ratio 1_0 samples persisted Long keys as Long`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output").also { Files.createDirectories(it) }

        // Run 1: write 5 sequence-based (Long) keys.
        val writeArgs = buildArgs(dir, outputDir, scenarioName = "writes", readRatio = 0.0)
        val logger = ScenarioRunnerCli.defaultLogger()
        val res1 = ScenarioRunnerCli.resolve(writeArgs, logger)
        val target1 = InMemoryTarget()
        ScenarioRunnerCli.run(writeArgs, res1, target1, logger)
        assertThat(target1.writes).hasSize(5)

        // Run 2: read-only, fresh process simulated by fresh resolve + fresh target.
        val readArgs = buildArgs(dir, outputDir, scenarioName = "reads", readRatio = 1.0)
        val res2 = ScenarioRunnerCli.resolve(readArgs, logger)
        val target2 = InMemoryTarget()
        ScenarioRunnerCli.run(readArgs, res2, target2, logger)

        // The runner only reads when keyRegistry has keys for the root schema. F11 closure
        // means restored keys are Long again, so reads happen and the sampled keys are Long.
        assertThat(target2.reads).isNotEmpty
        target2.reads.forEach { call ->
            assertThat(call.cacheName).isEqualTo("customer")
            assertThat(call.key)
                .isInstanceOf(java.lang.Long::class.java)
                .isIn(1L, 2L, 3L, 4L, 5L)
        }
    }

    private fun buildArgs(dir: Path, outputDir: Path, scenarioName: String, readRatio: Double): CliArgs {
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
              - name: writes
                target: t1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 100.0 }
                duration: { kind: count, value: 5 }
                read_ratio: 0.0
              - name: reads
                target: t1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 100.0 }
                duration: { kind: count, value: 10 }
                read_ratio: $readRatio
        """.trimIndent())

        val endpoints = dir.resolve("client-endpoints.yaml")
        if (!Files.exists(endpoints)) Files.writeString(endpoints, "clusters: []\n")

        return CliArgs(
            dataFile = dataFile,
            opsFile = opsFile,
            scenarioName = scenarioName,
            clusterEndpoints = endpoints,
            outputDir = outputDir,
            runGroup = "test-group",
            targetCluster = "test-cluster",
        )
    }
}
