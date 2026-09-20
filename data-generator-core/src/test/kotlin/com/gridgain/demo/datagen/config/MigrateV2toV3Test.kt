package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * data.yaml v3: every schema states its replication.
 *
 * A cache the generator provisions was created with the thin client's defaults — **no backups**,
 * `PRIMARY_SYNC` — because nothing in data.yaml could say otherwise. On a two-node cluster that
 * means one copy of every row, no redundancy to exercise, and a `put` that returns as soon as the
 * primary has it. A demo that claims to show replication has to be able to ask for it.
 *
 * The migration writes the values that reproduce exactly what a v2 file did, the same way ops v8
 * wrote `concurrency: 1`. Changing anyone's replication factor during a version bump would alter
 * both the durability and the write latency of a running demo without them asking.
 */
class MigrateV2toV3Test {

    private val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test
    fun `declares the step it performs`() {
        val m = MigrateV2toV3()
        assertThat(m.fromVersion).isEqualTo(2)
        assertThat(m.toVersion).isEqualTo(3)
        assertThat(m.description).contains("backups")
    }

    @Test
    fun `every schema gains the settings that preserve v2 behaviour`() {
        val yaml: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 2,
            "schemas" to mutableListOf(
                mutableMapOf<String, Any>("name" to "customer", "update_ratio" to 0.5),
                mutableMapOf<String, Any>("name" to "address", "update_ratio" to 0.0),
            ),
        )

        val out = MigrateV2toV3().migrate(yaml)

        @Suppress("UNCHECKED_CAST")
        val schemas = out["schemas"] as List<Map<String, Any>>
        assertThat(schemas).allSatisfy { s ->
            assertThat(s["backups"])
                .describedAs("what a v2 cache actually had: one copy, no redundancy")
                .isEqualTo(0)
            assertThat(s["write_synchronization_mode"])
                .describedAs("PRIMARY_SYNC is the thin client's default, so this is a no-op rewrite")
                .isEqualTo("primary_sync")
        }
    }

    @Test
    fun `a schema that already states them is left alone`() {
        // Idempotent, and it must not overwrite a deliberate choice if run twice.
        val yaml: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 2,
            "schemas" to mutableListOf(
                mutableMapOf<String, Any>(
                    "name" to "customer",
                    "backups" to 1,
                    "write_synchronization_mode" to "full_sync",
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val schemas = MigrateV2toV3().migrate(yaml)["schemas"] as List<Map<String, Any>>

        assertThat(schemas.single()["backups"]).isEqualTo(1)
        assertThat(schemas.single()["write_synchronization_mode"]).isEqualTo("full_sync")
    }

    @Test
    fun `a file with no schemas at all survives the step`() {
        val out = MigrateV2toV3().migrate(mutableMapOf("schema_version" to 2))
        assertThat(out).containsKey("schema_version")
    }

    @Test
    fun `the runner carries a v2 file to the current version`(@TempDir dir: Path) {
        val file = dir.resolve("data.yaml").also {
            it.writeText(
                """
                schema_version: 2
                schemas:
                  - name: customer
                    update_ratio: 0.5
                    columns: []
                """.trimIndent() + "\n"
            )
        }

        val text = DataConfigMigrationRunner.create()
            .ensureCurrentVersion(file.toFile(), CURRENT_DATA_SCHEMA_VERSION, logger)

        assertThat(text).contains("schema_version: $CURRENT_DATA_SCHEMA_VERSION")
        assertThat(text).contains("backups")
        assertThat(text).contains("primary_sync")
    }
}
