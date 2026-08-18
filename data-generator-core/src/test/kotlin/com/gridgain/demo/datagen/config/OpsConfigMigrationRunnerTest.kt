package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

class OpsConfigMigrationRunnerTest {
    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test
    fun `migrates a v1 ops file forward to v2`(@TempDir dir: Path) {
        val file = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 2, logger = logger)
        assertThat(text).contains("schema_version: 2")
        assertThat(text).containsPattern("scenarios:\\s*\\[\\s*\\]")
    }

    // v2 -> v3 is a no-op apart from the version bump: existing scenarios stay
    // single-pod by virtue of the new `distribution:` block being absent. The
    // migration is registered so legacy v2 files load cleanly under v3.

    @Test
    fun `migrates a v2 ops file forward to v3 without dropping scenarios`(@TempDir dir: Path) {
        val v2Body = """
            schema_version: 2
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v2Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 3, logger = logger)
        assertThat(text).contains("schema_version: 3")
        assertThat(text).contains("name: load")
        assertThat(text).contains("cluster_name: example-gcp-8a")
    }

    @Test
    fun `migrates a v1 ops file forward all the way to v3`(@TempDir dir: Path) {
        val file = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 3, logger = logger)
        assertThat(text).contains("schema_version: 3")
        assertThat(text).containsPattern("scenarios:\\s*\\[\\s*\\]")
    }

    // v3 -> v4 is a no-op apart from the version bump: the new optional `metrics:` block
    // (live throughput/latency export to Kafka) is absent in legacy files, so they keep the
    // existing "no live export" behaviour.
    @Test
    fun `migrates a v3 ops file forward to v4 without dropping scenarios`(@TempDir dir: Path) {
        val v3Body = """
            schema_version: 3
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v3Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 4, logger = logger)
        assertThat(text).contains("schema_version: 4")
        assertThat(text).contains("name: load")
        assertThat(text).contains("cluster_name: example-gcp-8a")
    }

    // v4 -> v5 is a no-op apart from the version bump: the new optional `control:` block
    // (runtime rate control over Kafka) is absent in legacy files, so they keep the existing
    // behaviour of the configured rate schedule pacing the whole run.
    @Test
    fun `migrates a v4 ops file forward to v5 preserving the metrics block`(@TempDir dir: Path) {
        val v4Body = """
            schema_version: 4
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
            metrics:
              kafka_bootstrap: kafka:9092
              topic: datagen-metrics
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v4Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 5, logger = logger)
        assertThat(text).contains("schema_version: 5")
        assertThat(text).contains("name: load")
        assertThat(text).contains("topic: datagen-metrics")
    }

    // v5 -> v6: the `metrics:` block gains two required keys, `histogram_highest_ms` and
    // `histogram_significant_digits` (the whole-run latency histogram's bounds). A v5 file that
    // declares `metrics:` predates them, so the migration fills in the JSONSchema-recommended
    // values rather than leaving the block invalid against the v6 schema.
    @Test
    fun `migrates a v5 ops file forward to v6 by adding the histogram bounds to metrics`(@TempDir dir: Path) {
        val v5Body = """
            schema_version: 5
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
            metrics:
              kafka_bootstrap: kafka:9092
              topic: datagen-metrics
              interval_ms: 500
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v5Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 6, logger = logger)
        assertThat(text).contains("schema_version: 6")
        assertThat(text).contains("topic: datagen-metrics")
        assertThat(text).contains("interval_ms: 500")
        assertThat(text).contains("histogram_highest_ms: 60000")
        assertThat(text).contains("histogram_significant_digits: 3")
    }

    @Test
    fun `a v5 ops file with no metrics block migrates to v6 without inventing one`(@TempDir dir: Path) {
        val v5Body = """
            schema_version: 5
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v5Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 6, logger = logger)
        assertThat(text).contains("schema_version: 6")
        assertThat(text).doesNotContain("metrics")
    }

    @Test
    fun `an explicit histogram bound already present is not overwritten by the migration`(@TempDir dir: Path) {
        val v5Body = """
            schema_version: 5
            scenarios:
              - name: load
                target: gg8-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 200 }
                read_ratio: 0.1
            targets:
              - name: gg8-trip
                kind: gg8-kv
                cluster_name: example-gcp-8a
            metrics:
              kafka_bootstrap: kafka:9092
              topic: datagen-metrics
              histogram_highest_ms: 5000
        """.trimIndent() + "\n"
        val file = dir.resolve("ops.yaml").also { it.writeText(v5Body) }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = 6, logger = logger)
        assertThat(text).contains("schema_version: 6")
        assertThat(text).contains("histogram_highest_ms: 5000")
        assertThat(text).contains("histogram_significant_digits: 3")
    }

    @Test
    fun `migrates a v1 ops file forward all the way to the current version`(@TempDir dir: Path) {
        val file = dir.resolve("ops.yaml").also { it.writeText("schema_version: 1\n") }
        val text = OpsConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), targetVersion = CURRENT_OPS_SCHEMA_VERSION, logger = logger)
        assertThat(text).contains("schema_version: $CURRENT_OPS_SCHEMA_VERSION")
    }
}
