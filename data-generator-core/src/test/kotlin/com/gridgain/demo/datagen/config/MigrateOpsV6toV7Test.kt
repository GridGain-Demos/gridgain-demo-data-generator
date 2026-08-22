package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.DataGenLogger
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV6toV7Test {

    /** Captures warnings so the discarded-mapping report can be asserted on. */
    private class RecordingLogger : DataGenLogger {
        val warnings = mutableListOf<String>()
        override fun lifecycle(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) { warnings += message }
        override fun error(message: String, throwable: Throwable?) = Unit
        override fun debug(message: String) = Unit
    }

    @Test
    fun `from and to versions are 6 and 7`() {
        val m = MigrateOpsV6toV7()
        assertThat(m.fromVersion).isEqualTo(6)
        assertThat(m.toVersion).isEqualTo(7)
        assertThat(m.description).contains("target")
    }

    @Test
    fun `removes the top-level targets block`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf(
                mutableMapOf("name" to "t1", "kind" to "gg8-kv", "cluster_name" to "c1")
            ),
            "scenarios" to mutableListOf<Any>(),
        )
        val out = MigrateOpsV6toV7().migrate(map)
        assertThat(out).doesNotContainKey("targets")
    }

    @Test
    fun `removes target from every scenario`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "scenarios" to mutableListOf(
                mutableMapOf("name" to "s1", "target" to "t1"),
                mutableMapOf("name" to "s2", "target" to "t2"),
            ),
        )
        val out = MigrateOpsV6toV7().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val scenarios = out["scenarios"] as List<Map<String, Any>>
        assertThat(scenarios).allSatisfy { assertThat(it).doesNotContainKey("target") }
        assertThat(scenarios.map { it["name"] }).containsExactly("s1", "s2")
    }

    @Test
    fun `warns naming each discarded scenario-to-cluster mapping`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf(
                mutableMapOf("name" to "t1", "kind" to "gg8-kv", "cluster_name" to "prod-gg8")
            ),
            "scenarios" to mutableListOf(
                mutableMapOf("name" to "load", "target" to "t1")
            ),
        )
        MigrateOpsV6toV7(logger).migrate(map)

        assertThat(logger.warnings).hasSize(1)
        assertThat(logger.warnings.single())
            .contains("load")
            .contains("prod-gg8")
            .contains("--target-cluster")
    }

    @Test
    fun `is silent and idempotent on a file that never had targets`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "scenarios" to mutableListOf(mutableMapOf("name" to "s1")),
        )
        val out = MigrateOpsV6toV7(logger).migrate(map)

        assertThat(out).doesNotContainKey("targets")
        assertThat(logger.warnings).isEmpty()
    }

    @Test
    fun `reports a scenario whose target does not resolve`() {
        val logger = RecordingLogger()
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 6,
            "targets" to mutableListOf<Any>(),
            "scenarios" to mutableListOf(mutableMapOf("name" to "s1", "target" to "ghost")),
        )
        MigrateOpsV6toV7(logger).migrate(map)

        assertThat(logger.warnings.single()).contains("s1").contains("ghost")
    }
}
