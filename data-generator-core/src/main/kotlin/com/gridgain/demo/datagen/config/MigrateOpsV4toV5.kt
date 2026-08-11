package com.gridgain.demo.datagen.config

/**
 * No-op v4 -> v5 migration. The only schema change in v5 is the addition of an optional top-level
 * `control:` block (runtime rate control over Kafka); absence keeps the existing behaviour (the
 * scenario's configured rate schedule paces the whole run), so v4 documents are valid v5 documents
 * apart from the version pointer. `ConfigMigrationRunner` writes the new version itself.
 */
class MigrateOpsV4toV5 : ConfigMigration {
    override val fromVersion: Int = 4
    override val toVersion: Int = 5
    override val description: String = "add optional control block (no value rewrite required)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> = yaml
}
