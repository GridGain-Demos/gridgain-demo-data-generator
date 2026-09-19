package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV7toV8Test {

    @Test
    fun `from and to versions are 7 and 8`() {
        val m = MigrateOpsV7toV8()
        assertThat(m.fromVersion).isEqualTo(7)
        assertThat(m.toVersion).isEqualTo(8)
        assertThat(m.description).contains("concurrency")
    }

    @Test
    fun `every scenario gains concurrency 1, preserving today's single-threaded behaviour`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 7,
            "scenarios" to mutableListOf(
                mutableMapOf<String, Any>("name" to "s1"),
                mutableMapOf<String, Any>("name" to "s2"),
            ),
        )

        val out = MigrateOpsV7toV8().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val scenarios = out["scenarios"] as List<Map<String, Any>>
        assertThat(scenarios.map { it["concurrency"] })
            .describedAs("a migrated file must run exactly as it did before the upgrade")
            .containsExactly(1, 1)
    }

    @Test
    fun `a concurrency already set by hand is not reverted`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 7,
            "scenarios" to mutableListOf(
                mutableMapOf<String, Any>("name" to "s1", "concurrency" to 64),
            ),
        )

        val out = MigrateOpsV7toV8().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val scenarios = out["scenarios"] as List<Map<String, Any>>
        assertThat(scenarios.single()["concurrency"]).isEqualTo(64)
    }

    @Test
    fun `a file with no scenarios is left alone`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 7)

        val out = MigrateOpsV7toV8().migrate(map)

        assertThat(out).doesNotContainKey("scenarios")
    }

    @Test
    fun `a malformed scenario entry is skipped rather than crashing the migration`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 7,
            "scenarios" to mutableListOf<Any>(
                "not a mapping",
                mutableMapOf<String, Any>("name" to "s1"),
            ),
        )

        val out = MigrateOpsV7toV8().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val scenarios = out["scenarios"] as List<Any>
        assertThat(scenarios).hasSize(2)
        @Suppress("UNCHECKED_CAST")
        val valid = scenarios[1] as Map<String, Any>
        assertThat(valid["concurrency"])
            .describedAs("the v8 JSONSchema rejects the malformed entry; the valid one still migrates")
            .isEqualTo(1)
    }
}
