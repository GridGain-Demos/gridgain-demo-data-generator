package com.gridgain.demo.datagen.config

/**
 * v7 -> v8: every scenario gains a required `concurrency` — how many worker threads one generator
 * process drives.
 *
 * Until v8 a run was one JVM with a single emitting thread, so its throughput was capped by the
 * round-trip latency of the target rather than by anything the operator configured; the only way
 * to add load was to launch more processes. `concurrency` lets one process hold many operations
 * in flight, which is what makes it possible to saturate a fast cluster.
 *
 * Every migrated scenario is written `1` explicitly, so a file upgraded by this step runs exactly
 * as it did before — an upgrade must not silently multiply the load a demo produces. As in
 * [MigrateOpsV5toV6] that is a *migration* value, not a parse-time fallback: afterwards the file
 * states the value, and [ScenarioSpec] still refuses to deserialize a document that omits it.
 * A value already present is preserved, so hand-tuning ahead of the migration is not reverted.
 *
 * Nothing is lost here, so unlike [MigrateOpsV6toV7] this migration reports nothing.
 */
class MigrateOpsV7toV8 : ConfigMigration {

    override val fromVersion: Int = 7
    override val toVersion: Int = 8
    override val description: String =
        "add required per-scenario concurrency (worker threads per generator process)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val scenarios = yaml["scenarios"] as? MutableList<Any> ?: return yaml
        for (element in scenarios) {
            // A non-mapping entry is malformed; leave it for the v8 JSONSchema to reject by name
            // rather than crash the whole migration on one bad scenario.
            @Suppress("UNCHECKED_CAST")
            val scenario = element as? MutableMap<String, Any> ?: continue
            scenario.putIfAbsent("concurrency", DEFAULT_CONCURRENCY)
        }
        return yaml
    }

    private companion object {
        /** Keep in step with the `default` recorded in `schema/ops/v8.schema.json`. */
        const val DEFAULT_CONCURRENCY = 1
    }
}
