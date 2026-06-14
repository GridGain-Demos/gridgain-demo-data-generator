package com.gridgain.demo.datagen.config

/**
 * No-op v3 -> v4 migration. The only schema change in v4 is the addition of an optional
 * top-level `metrics:` block (live throughput/latency export to Kafka); absence keeps the
 * existing behaviour (no live export), so v3 documents are valid v4 documents apart from the
 * version pointer. `ConfigMigrationRunner` writes the new version itself.
 */
class MigrateOpsV3toV4 : ConfigMigration {
    override val fromVersion: Int = 3
    override val toVersion: Int = 4
    override val description: String = "add optional metrics block (no value rewrite required)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> = yaml
}
