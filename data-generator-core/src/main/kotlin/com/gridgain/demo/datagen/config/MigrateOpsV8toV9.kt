package com.gridgain.demo.datagen.config

/**
 * v8 -> v9: `metrics:` and `control:` stop carrying a bare `kafka_bootstrap` address and carry a
 * `broker:` reference instead, which may either **name a deployed `message_brokers` element** or
 * state a literal address.
 *
 * Why the change: the address in a v8 file was a hand-copied duplicate of something the toolkit
 * already knew. It went stale silently when a broker was redeployed elsewhere, and nothing
 * reconciled the two. A `kind: element` reference is resolved at launch from the
 * `broker-endpoints.yaml` the toolkit writes, so there is one definition of where the broker is.
 *
 * This migration is a mechanical, lossless rewrite rather than a version pointer:
 *
 *     kafka_bootstrap: X   ->   broker: { kind: address, bootstrap_servers: X }
 *
 * **It never invents an element name.** `10.0.0.5:9092` does not identify `payments-bus` — only a
 * deployment does — and a guess would write a reference to a broker that may not exist, turning a
 * working file into one that fails at launch on a name the operator never typed. The v8 literal
 * *is* the address branch, so nothing here is interpreted. An operator who wants the reference
 * form edits the two lines afterwards; that is a deliberate manual step.
 *
 * Note for anyone hand-bumping `schema_version` to preserve comments, as the Power lab's ops.yaml
 * is maintained: unlike 7 -> 8, a hand-bump alone is **not** equivalent to running this. The two
 * `kafka_bootstrap` keys have to be rewritten by hand as well, or the v9 JSONSchema rejects the
 * file for a missing `broker`.
 *
 * A block that already carries `broker:` is left alone, so a partially hand-migrated file is not
 * clobbered. A malformed block is skipped for the v9 JSONSchema to reject by name, as
 * [MigrateOpsV7toV8] does for a malformed scenario.
 */
class MigrateOpsV8toV9 : ConfigMigration {

    override val fromVersion: Int = 8
    override val toVersion: Int = 9
    override val description: String =
        "replace metrics/control kafka_bootstrap with a broker reference (element name or address)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        for (blockName in CHANNEL_BLOCKS) {
            @Suppress("UNCHECKED_CAST")
            val block = yaml[blockName] as? MutableMap<String, Any> ?: continue
            if (block.containsKey(BROKER_KEY)) continue
            val address = block.remove(LEGACY_ADDRESS_KEY) ?: continue
            block[BROKER_KEY] = mutableMapOf<String, Any>(
                "kind" to ADDRESS_KIND,
                "bootstrap_servers" to address,
            )
        }
        return yaml
    }

    private companion object {
        /** The two blocks that name a broker. Both optional, both the same shape. */
        val CHANNEL_BLOCKS = listOf("metrics", "control")
        const val BROKER_KEY = "broker"
        const val LEGACY_ADDRESS_KEY = "kafka_bootstrap"
        const val ADDRESS_KIND = "address"
    }
}
