package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV8toV9Test {

    @Test
    fun `from and to versions are 8 and 9`() {
        val m = MigrateOpsV8toV9()
        assertThat(m.fromVersion).isEqualTo(8)
        assertThat(m.toVersion).isEqualTo(9)
        assertThat(m.description).contains("broker")
    }

    @Test
    fun `a metrics kafka_bootstrap becomes an address broker ref`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "metrics" to mutableMapOf<String, Any>(
                "kafka_bootstrap" to "10.0.0.5:9092",
                "topic" to "datagen-metrics",
            ),
        )

        val out = MigrateOpsV8toV9().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val metrics = out["metrics"] as Map<String, Any>
        assertThat(metrics).doesNotContainKey("kafka_bootstrap")
        assertThat(metrics["broker"]).isEqualTo(
            mapOf("kind" to "address", "bootstrap_servers" to "10.0.0.5:9092")
        )
        // Everything else in the block is untouched.
        assertThat(metrics["topic"]).isEqualTo("datagen-metrics")
    }

    @Test
    fun `a control kafka_bootstrap becomes an address broker ref`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "control" to mutableMapOf<String, Any>(
                "kafka_bootstrap" to "10.0.0.5:9092",
                "topic" to "datagen-control",
            ),
        )

        val out = MigrateOpsV8toV9().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val control = out["control"] as Map<String, Any>
        assertThat(control["broker"]).isEqualTo(
            mapOf("kind" to "address", "bootstrap_servers" to "10.0.0.5:9092")
        )
    }

    @Test
    fun `an address is never turned into an element name`() {
        // The migration cannot know that 10.0.0.5:9092 is 'payments-bus' — only a deployment does.
        // Guessing would write a reference to a broker that may not exist, and the run would then
        // fail at launch on a name the operator never typed.
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "metrics" to mutableMapOf<String, Any>("kafka_bootstrap" to "10.0.0.5:9092"),
        )

        val out = MigrateOpsV8toV9().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val broker = (out["metrics"] as Map<String, Any>)["broker"] as Map<String, Any>
        assertThat(broker["kind"]).isEqualTo("address")
        assertThat(broker).doesNotContainKey("name")
    }

    @Test
    fun `absent metrics and control blocks are left alone`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 8)

        val out = MigrateOpsV8toV9().migrate(map)

        assertThat(out).doesNotContainKey("metrics")
        assertThat(out).doesNotContainKey("control")
    }

    @Test
    fun `a block already carrying a broker ref is not rewritten`() {
        val existing = mutableMapOf<String, Any>("kind" to "element", "name" to "payments-bus")
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "metrics" to mutableMapOf<String, Any>("broker" to existing, "topic" to "t"),
        )

        val out = MigrateOpsV8toV9().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val metrics = out["metrics"] as Map<String, Any>
        assertThat(metrics["broker"]).isEqualTo(existing)
    }

    @Test
    fun `a malformed block is skipped rather than crashing the migration`() {
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "metrics" to "not a mapping",
        )

        val out = MigrateOpsV8toV9().migrate(map)

        // Left for the v9 JSONSchema to reject by name.
        assertThat(out["metrics"]).isEqualTo("not a mapping")
    }

    @Test
    fun `a block with neither key is left for the schema to reject`() {
        // 'broker' is required in v9, so an incomplete block must fail validation rather than be
        // quietly completed with an invented address.
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 8,
            "control" to mutableMapOf<String, Any>("topic" to "datagen-control"),
        )

        val out = MigrateOpsV8toV9().migrate(map)

        @Suppress("UNCHECKED_CAST")
        val control = out["control"] as Map<String, Any>
        assertThat(control).doesNotContainKey("broker")
    }
}
