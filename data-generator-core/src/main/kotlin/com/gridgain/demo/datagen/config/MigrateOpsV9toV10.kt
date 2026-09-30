package com.gridgain.demo.datagen.config

/**
 * v9 -> v10: every scenario gains `operations`, `warmup` and `key_space`, and loses `read_ratio`.
 *
 * These are the three things a scenario needed before it could describe a benchmark-shaped run
 * rather than a stream of data:
 *
 *  - **`operations`** replaces the `read_ratio` scalar with a weight map over `put`, `get` and
 *    `put_get`. A single ratio could only ever say "some fraction are reads", and had no way to name
 *    a third operation — which is why a get-then-put against **one key**, the read-modify-write a
 *    real application performs, could not be expressed at all.
 *  - **`warmup`** marks operations that are performed but not measured. Without it every figure the
 *    generator produced included its own JIT compilation and connection setup, and the shorter the
 *    run the more that cold start dominated.
 *  - **`key_space`** bounds the domain of keys the scenario addresses. Unbounded, a get can only
 *    find a row this same process wrote during this same run, so no two runs ever address the same
 *    data and a read-heavy scenario can miss on nearly every operation while reporting a healthy
 *    rate.
 *
 * ### What a migrated file does
 *
 * Exactly what it did before. `warmup: {kind: none}` and `key_space: {kind: unbounded}` are the
 * pre-v10 behaviours written down, and `read_ratio: r` becomes `{get: r, put: 1-r, put_get: 0}` —
 * the same mix, stated in the new vocabulary. An upgrade must never silently change the workload a
 * demo produces.
 *
 * As in [MigrateOpsV7toV8] these are *migration* values, not parse-time fallbacks: afterwards the
 * file states them, and [ScenarioSpec] still refuses to deserialize a document that omits any of
 * the three. Values already present are preserved, so hand-tuning ahead of the migration survives.
 *
 * ⚠️ **This bump invalidates every deployed archive and image**, as v7, v8 and v9 did before it: a
 * pre-v10 archive refuses a v10 file outright. Rebuild and redeploy the fleet in the same pass as
 * the ops file.
 */
class MigrateOpsV9toV10 : ConfigMigration {

    override val fromVersion: Int = 9
    override val toVersion: Int = 10
    override val description: String =
        "replace read_ratio with an operations weight map; add warmup and key_space"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val scenarios = yaml["scenarios"] as? MutableList<Any> ?: return yaml
        for (element in scenarios) {
            // A non-mapping entry is malformed; leave it for the v10 JSONSchema to reject by name
            // rather than crash the whole migration on one bad scenario.
            @Suppress("UNCHECKED_CAST")
            val scenario = element as? MutableMap<String, Any> ?: continue

            scenario.putIfAbsent("warmup", linkedMapOf<String, Any>("kind" to "none"))
            scenario.putIfAbsent("key_space", linkedMapOf<String, Any>("kind" to "unbounded"))

            // `remove` rather than leave-in-place: v10's schema sets additionalProperties false, so
            // a surviving read_ratio would fail validation with a message about an unexpected key
            // rather than about the migration.
            val readRatio = toRatio(scenario.remove("read_ratio"))
            scenario.putIfAbsent(
                "operations",
                linkedMapOf<String, Any>(
                    "put" to 1.0 - readRatio,
                    "get" to readRatio,
                    // Stated explicitly even though it is zero. All three weights are required in
                    // v10, and a reader comparing two scenarios should not have to know which
                    // operations a migration happened to omit.
                    "put_get" to 0.0,
                ),
            )
        }
        return yaml
    }

    /**
     * A malformed or absent `read_ratio` becomes zero — an all-writes scenario.
     *
     * Not a silent repair of a broken file: v9's own schema required the key and constrained it to
     * `0.0..1.0`, so anything else here cannot have validated as v9. Choosing a value lets the
     * migration finish and hand the document to the v10 schema, which reports the real problem by
     * name; throwing here would blame the migration for a file that was already invalid.
     */
    private fun toRatio(raw: Any?): Double {
        val value = (raw as? Number)?.toDouble() ?: return 0.0
        if (value.isNaN() || value < 0.0 || value > 1.0) return 0.0
        return value
    }
}
