package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import kotlin.test.Test

/**
 * v9 -> v10, whose single obligation is that **an upgraded file runs the workload it ran before**.
 *
 * A migration that silently changed a demo's operation mix would be worse than one that failed: the
 * failure is visible, the drift is not, and every figure taken after it would be compared against
 * figures taken before it.
 */
class MigrateOpsV9toV10Test {

    private val migration = MigrateOpsV9toV10()

    private fun scenario(vararg pairs: Pair<String, Any>): MutableMap<String, Any> =
        linkedMapOf(
            "name" to "s1",
            "root_schemas" to mutableListOf("customer"),
            "concurrency" to 4,
            *pairs,
        )

    private fun migrate(vararg scenarios: MutableMap<String, Any>): List<Map<String, Any>> {
        val doc: MutableMap<String, Any> =
            linkedMapOf("schema_version" to 9, "scenarios" to scenarios.toMutableList<Any>())
        @Suppress("UNCHECKED_CAST")
        return migration.migrate(doc)["scenarios"] as List<Map<String, Any>>
    }

    // -----------------------------------------------------------------------
    // read_ratio -> operations
    // -----------------------------------------------------------------------

    @Test
    fun `a mixed read ratio becomes the same mix as weights`() {
        val migrated = migrate(scenario("read_ratio" to 0.2)).single()

        @Suppress("UNCHECKED_CAST")
        val operations = migrated["operations"] as Map<String, Any>
        assertThat(operations["get"] as Double).isCloseTo(0.2, within(1e-9))
        assertThat(operations["put"] as Double)
            .describedAs("the write share is the complement; anything else changes the workload")
            .isCloseTo(0.8, within(1e-9))
        assertThat(operations["put_get"] as Double)
            .describedAs("put_get did not exist before v10, so a migrated file cannot have any")
            .isEqualTo(0.0)
    }

    @Test
    fun `an all-writes scenario stays all writes`() {
        @Suppress("UNCHECKED_CAST")
        val operations = migrate(scenario("read_ratio" to 0.0)).single()["operations"] as Map<*, *>
        assertThat(operations["put"]).isEqualTo(1.0)
        assertThat(operations["get"]).isEqualTo(0.0)
    }

    @Test
    fun `an all-reads scenario stays all reads`() {
        @Suppress("UNCHECKED_CAST")
        val operations = migrate(scenario("read_ratio" to 1.0)).single()["operations"] as Map<*, *>
        assertThat(operations["put"]).isEqualTo(0.0)
        assertThat(operations["get"]).isEqualTo(1.0)
    }

    /** An integer in the YAML is still a number; SnakeYAML types `1` as Int, not Double. */
    @Test
    fun `an integer read ratio is read as a number`() {
        @Suppress("UNCHECKED_CAST")
        val operations = migrate(scenario("read_ratio" to 1)).single()["operations"] as Map<*, *>
        assertThat(operations["get"]).isEqualTo(1.0)
    }

    /**
     * The weights the migration writes must satisfy the parse-time rule it is writing them for.
     *
     * A migration that produced a document its own schema rejects would turn an upgrade into an
     * unexplained validation failure, which is exactly what a migration exists to prevent.
     */
    @Test
    fun `the weights it writes always sum to one`() {
        listOf(0.0, 0.1, 0.2, 0.333, 0.5, 0.75, 0.99, 1.0).forEach { ratio ->
            @Suppress("UNCHECKED_CAST")
            val operations = migrate(scenario("read_ratio" to ratio)).single()["operations"] as Map<String, Any>
            // Constructing it is the assertion: OperationMix refuses weights that do not sum to 1.0.
            OperationMix(
                put = operations["put"] as Double,
                get = operations["get"] as Double,
                putGet = operations["put_get"] as Double,
            )
        }
    }

    @Test
    fun `read_ratio is removed, not left beside its replacement`() {
        val migrated = migrate(scenario("read_ratio" to 0.5)).single()
        assertThat(migrated)
            .describedAs("v10 sets additionalProperties false, so a survivor fails validation")
            .doesNotContainKey("read_ratio")
    }

    // -----------------------------------------------------------------------
    // The two additions, both writing the pre-v10 behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `warmup and key_space are written as the behaviour the file already had`() {
        val migrated = migrate(scenario("read_ratio" to 0.0)).single()

        assertThat(migrated["warmup"])
            .describedAs("a migration must not start discarding measurements nobody asked it to")
            .isEqualTo(mapOf("kind" to "none"))
        assertThat(migrated["key_space"])
            .describedAs("bounding the key space would change which rows the demo writes")
            .isEqualTo(mapOf("kind" to "unbounded"))
    }

    // -----------------------------------------------------------------------
    // Hand-tuning ahead of the migration survives it
    // -----------------------------------------------------------------------

    @Test
    fun `values already present are preserved`() {
        val hand = scenario(
            "read_ratio" to 0.5,
            "operations" to linkedMapOf("put" to 0.1, "get" to 0.1, "put_get" to 0.8),
            "warmup" to linkedMapOf("kind" to "count", "value" to 5_000),
            "key_space" to linkedMapOf("kind" to "bounded", "size" to 10_000, "distribution" to "zipfian"),
        )
        val migrated = migrate(hand).single()

        @Suppress("UNCHECKED_CAST")
        assertThat((migrated["operations"] as Map<String, Any>)["put_get"])
            .describedAs("a hand-written mix must outrank one derived from the old scalar")
            .isEqualTo(0.8)
        assertThat(migrated["warmup"]).isEqualTo(mapOf("kind" to "count", "value" to 5_000))
        assertThat(migrated["key_space"])
            .isEqualTo(mapOf("kind" to "bounded", "size" to 10_000, "distribution" to "zipfian"))
        assertThat(migrated).doesNotContainKey("read_ratio")
    }

    // -----------------------------------------------------------------------
    // Malformed input is handed on rather than crashed on
    // -----------------------------------------------------------------------

    /**
     * A `read_ratio` outside `0.0..1.0` could not have validated as v9, so the file was already
     * broken. The migration finishes anyway and lets the v10 schema report the real problem by name
     * — throwing here would blame the migration for damage done earlier.
     */
    @Test
    fun `an out-of-range read ratio still yields a valid document`() {
        listOf<Any>(-1.0, 7.0, "banana").forEach { bad ->
            @Suppress("UNCHECKED_CAST")
            val operations = migrate(scenario("read_ratio" to bad)).single()["operations"] as Map<String, Any>
            OperationMix(
                put = operations["put"] as Double,
                get = operations["get"] as Double,
                putGet = operations["put_get"] as Double,
            )
        }
    }

    @Test
    fun `a non-mapping scenario entry is left for the schema to reject`() {
        val doc: MutableMap<String, Any> =
            linkedMapOf("schema_version" to 9, "scenarios" to mutableListOf<Any>("not-a-mapping"))
        assertThat(migration.migrate(doc)["scenarios"]).isEqualTo(listOf("not-a-mapping"))
    }

    @Test
    fun `a document with no scenarios is untouched`() {
        val doc: MutableMap<String, Any> = linkedMapOf("schema_version" to 9)
        assertThat(migration.migrate(doc)).isEqualTo(mapOf("schema_version" to 9))
    }

    @Test
    fun `it declares the versions it bridges`() {
        assertThat(migration.fromVersion).isEqualTo(9)
        assertThat(migration.toVersion).isEqualTo(10)
        assertThat(migration.toVersion).isEqualTo(CURRENT_OPS_SCHEMA_VERSION)
    }
}
