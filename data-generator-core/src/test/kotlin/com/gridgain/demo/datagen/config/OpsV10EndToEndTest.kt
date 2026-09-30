package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A v9 `ops.yaml` on disk, through the real migration runner and the real JSONSchema, to a parsed
 * [ScenarioSpec].
 *
 * The per-step tests either side of this one each prove their own step; none of them proves the
 * steps compose. The v10 schema is a separate file resolved by version number, so a migration that
 * writes a shape the schema does not accept — or a schema that requires a key the migration does not
 * write — fails only here, and would otherwise surface as a validation error on the operator's
 * machine on the day they upgrade.
 */
class OpsV10EndToEndTest {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    /** A single-schema data.yaml with a `sequence` key — the shape bounded mode requires. */
    private val dataYaml = """
        schema_version: 3
        schemas:
          - name: item
            update_ratio: 0.0
            backups: 1
            write_synchronization_mode: full_sync
            columns:
              - name: id
                null_rate: 0.0
                key: true
                value_source: { kind: sequence, start: 1, step: 1 }
    """

    private fun write(dir: Path, body: String): Path {
        Files.writeString(dir.resolve("data.yaml"), dataYaml.trimIndent())
        return dir.resolve("ops.yaml").also { Files.writeString(it, body.trimIndent()) }
    }

    private fun parse(opsFile: Path): OpsConfig = ConfigurationParser(logger = logger)
        .parse(dataFile = opsFile.parent.resolve("data.yaml").toFile(), opsFile = opsFile.toFile())
        .ops

    private val v9File = """
        schema_version: 9
        scenarios:
          - name: mixed
            root_schemas: [item]
            rate: { kind: constant, ops_per_second: 1000 }
            duration: { kind: time, value: "PT10M" }
            concurrency: 8
            read_ratio: 0.25
            transaction_scope: none
    """

    @Test
    fun `a v9 file migrates, validates and parses into the mix it always ran`(@TempDir dir: Path) {
        val file = write(dir, v9File)
        val ops = parse(file)

        val scenario = ops.scenarios.single()
        assertThat(scenario.operations.get).isCloseTo(0.25, within(1e-9))
        assertThat(scenario.operations.put).isCloseTo(0.75, within(1e-9))
        assertThat(scenario.operations.putGet).isEqualTo(0.0)
        assertThat(scenario.warmup).isEqualTo(NoWarmupSpec())
        assertThat(scenario.keySpace).isEqualTo(UnboundedKeySpaceSpec())
        assertThat(scenario.concurrency).isEqualTo(8)

        assertThat(Files.readString(file))
            .describedAs("the migration rewrites the file in place, so it is now a v10 document")
            .contains("schema_version: 10")
    }

    @Test
    fun `a benchmark-shaped v10 file parses as written`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: put-benchmark
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 1000000 }
                duration: { kind: time, value: "PT300S" }
                warmup: { kind: time, value: "PT60S" }
                key_space: { kind: bounded, size: 1000000, distribution: uniform }
                operations: { put: 1.0, get: 0.0, put_get: 0.0 }
                concurrency: 8
        """)
        val scenario = parse(file).scenarios.single()

        assertThat(scenario.warmup).isEqualTo(TimeWarmupSpec("PT60S"))
        assertThat(scenario.keySpace)
            .isEqualTo(BoundedKeySpaceSpec(size = 1_000_000, distribution = KeyDistribution.UNIFORM))
        assertThat(scenario.operations.put).isEqualTo(1.0)
    }

    @Test
    fun `a put_get scenario parses`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: put-get-benchmark
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 1000000 }
                duration: { kind: count, value: 1000 }
                warmup: { kind: count, value: 100 }
                key_space: { kind: bounded, size: 500, distribution: zipfian }
                operations: { put: 0.0, get: 0.0, put_get: 1.0 }
                concurrency: 2
        """)
        val scenario = parse(file).scenarios.single()

        assertThat(scenario.operations.putGet).isEqualTo(1.0)
        assertThat(scenario.warmup).isEqualTo(CountWarmupSpec(100))
        assertThat(scenario.keySpace)
            .isEqualTo(BoundedKeySpaceSpec(size = 500, distribution = KeyDistribution.ZIPFIAN))
    }

    // -----------------------------------------------------------------------
    // The schema has to reject what the Kotlin layer would reject
    // -----------------------------------------------------------------------

    @Test
    fun `a leftover read_ratio is rejected rather than ignored`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: stale
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 1 }
                warmup: { kind: none }
                key_space: { kind: unbounded }
                operations: { put: 1.0, get: 0.0, put_get: 0.0 }
                concurrency: 1
                read_ratio: 0.5
        """)
        assertThatThrownBy { parse(file) }
            .describedAs(
                "a hand-edited file that bumped the version but kept read_ratio would otherwise " +
                    "run an all-writes workload while appearing to ask for reads"
            )
            .hasMessageContaining("read_ratio")
    }

    @Test
    fun `a bounded key space without a size is rejected`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: no-size
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 1 }
                warmup: { kind: none }
                key_space: { kind: bounded, distribution: uniform }
                operations: { put: 1.0, get: 0.0, put_get: 0.0 }
                concurrency: 1
        """)
        assertThatThrownBy { parse(file) }
            .hasMessageContaining("key_space")
    }

    @Test
    fun `an unknown distribution is rejected by name`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: bad-dist
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 1 }
                warmup: { kind: none }
                key_space: { kind: bounded, size: 10, distribution: gaussian }
                operations: { put: 1.0, get: 0.0, put_get: 0.0 }
                concurrency: 1
        """)
        assertThatThrownBy { parse(file) }
            .hasMessageContaining("distribution")
    }

    @Test
    fun `weights that do not sum to one are rejected with the sum named`(@TempDir dir: Path) {
        val file = write(dir, """
            schema_version: 10
            scenarios:
              - name: bad-weights
                root_schemas: [item]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 1 }
                warmup: { kind: none }
                key_space: { kind: unbounded }
                operations: { put: 0.5, get: 0.2, put_get: 0.0 }
                concurrency: 1
        """)
        assertThatThrownBy { parse(file) }
            .hasMessageContaining("must sum to 1.0")
    }
}
