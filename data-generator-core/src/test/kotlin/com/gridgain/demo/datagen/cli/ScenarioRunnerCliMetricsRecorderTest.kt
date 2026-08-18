package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Regression guard for the window Task 4's [com.gridgain.demo.datagen.metrics.LiveMetricsReporter]
 * guard opened: `ScenarioRunnerCli` used to build `MetricsRecorder.detached()` unconditionally, and
 * then attach a real `LiveMetricsReporter` to that same instance whenever ops.yaml declared a
 * `metrics:` block — which throws `IllegalArgumentException` at startup (see
 * `LiveMetricsReporter.init`). No test drove that path, so `./gradlew build` stayed green while
 * every metrics-configured run was broken.
 *
 * Unlike an earlier version of this test, which hand-copied `ScenarioRunnerCli`'s
 * recorder-building expression into the test body, this drives the real
 * `ScenarioRunnerCli.resolve()`/`run()` path — the same one production uses — with
 * [InMemoryTarget] standing in for a live cluster, following `ScenarioRunnerCliRunLogTest`'s
 * pattern. A revert of `ScenarioRunnerCli`'s `metrics?.let { ... } ?: detached()` back to an
 * unconditional `MetricsRecorder.detached()` makes this test fail with the exact
 * `IllegalArgumentException` the regression produced in production; a hand-copied expression
 * cannot detect that revert because it never calls the CLI's code at all.
 *
 * `kafka_bootstrap` points at an unroutable local address rather than a live broker.
 * `KafkaMetricsSink` is fire-and-forget (`acks=0`, `max.block.ms=2000`) and `LiveMetricsReporter`
 * swallows sink failures per tick (`runCatching { sink.emit(snapshot) }`), so the unreachable
 * broker costs a bounded few seconds of connection-refused/metadata-timeout latency on `close()`,
 * not a hang — verified empirically: this test completes in well under `max.block.ms` +
 * the producer's close timeout.
 */
class ScenarioRunnerCliMetricsRecorderTest {

    @Test
    fun `run wires a real recorder to the reporter when ops_yaml declares a metrics block`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output").also { Files.createDirectories(it) }
        val args = buildCliArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution = ScenarioRunnerCli.resolve(args, logger)

        // Sanity: the fixture actually exercises the metrics path, not an accidentally-absent one.
        assertThat(resolution.parsedConfig.ops.metrics).isNotNull()

        val target = InMemoryTarget()
        // Before Task 6, this threw IllegalArgumentException the instant LiveMetricsReporter's
        // init guard ran, because the CLI still handed it a detached recorder.
        assertThatCode {
            ScenarioRunnerCli.run(args, resolution, target, logger)
        }.doesNotThrowAnyException()
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
            metrics:
              kafka_bootstrap: "127.0.0.1:1"
              topic: datagen-metrics-test
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
            runGroup = "test-group",
        )
    }
}
