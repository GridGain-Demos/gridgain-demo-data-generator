package com.gridgain.demo.datagen.config

/**
 * No-op v2 -> v3 migration. The only schema change in v3 is the addition of an
 * optional `distribution:` block on each scenario; absence keeps the existing
 * single-pod semantics, so v2 documents are valid v3 documents apart from the
 * version pointer. `ConfigMigrationRunner` writes the new version itself.
 */
class MigrateOpsV2toV3 : ConfigMigration {
    override val fromVersion: Int = 2
    override val toVersion: Int = 3
    override val description: String = "add optional distribution block (no value rewrite required)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> = yaml
}
