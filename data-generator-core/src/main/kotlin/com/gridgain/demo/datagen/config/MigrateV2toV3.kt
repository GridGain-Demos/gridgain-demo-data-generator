package com.gridgain.demo.datagen.config

/**
 * Every schema states its replication: `backups` and `write_synchronization_mode`.
 *
 * Before this, a cache the generator provisioned took the thin client's defaults — no backups,
 * `PRIMARY_SYNC` — because data.yaml had no way to say otherwise. On a multi-node cluster that is
 * one copy of every row and a `put` that returns as soon as the primary has it, which is not a
 * demo of anything a distributed database does.
 *
 * Writes the values that reproduce v2 behaviour exactly, the way [MigrateOpsV7toV8] wrote
 * `concurrency: 1`. Raising anyone's replication factor during a version bump would change both
 * the durability and the write latency of a running demo without them asking for either.
 *
 * Idempotent: a schema that already states a value keeps it.
 */
class MigrateV2toV3 : ConfigMigration {
    override val fromVersion: Int = 2
    override val toVersion: Int = 3
    override val description: String =
        "state backups and write_synchronization_mode on every schema (v2 defaults: 0, primary_sync)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        val schemas = yaml["schemas"] as? MutableList<*> ?: return yaml
        schemas.forEach { schema ->
            @Suppress("UNCHECKED_CAST")
            val map = schema as? MutableMap<String, Any> ?: return@forEach
            map.putIfAbsent("backups", 0)
            map.putIfAbsent("write_synchronization_mode", "primary_sync")
        }
        return yaml
    }
}
