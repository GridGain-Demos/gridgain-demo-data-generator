package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.state.KeyRegistryState
import java.util.Random

class KeyRegistry {

    private val keysBySchema: MutableMap<String, MutableList<Any>> = mutableMapOf()
    private val seenBySchema: MutableMap<String, MutableSet<Any>> = mutableMapOf()

    fun register(schemaName: String, key: Any) {
        val seen = seenBySchema.getOrPut(schemaName) { mutableSetOf() }
        if (seen.add(key)) {
            keysBySchema.getOrPut(schemaName) { mutableListOf() }.add(key)
        }
    }

    fun sample(schemaName: String, random: Random): Any? {
        val keys = keysBySchema[schemaName] ?: return null
        if (keys.isEmpty()) return null
        return keys[random.nextInt(keys.size)]
    }

    fun size(schemaName: String): Int = keysBySchema[schemaName]?.size ?: 0

    /**
     * Captures the current registry as a list of [KeyRegistryState], one entry per schema.
     * Keys are converted to `String` via `toString()` for JSON-safety. Schemas are
     * deterministically ordered for stable yaml output. F11 covers type-fidelity beyond
     * sampling.
     */
    fun snapshot(): List<KeyRegistryState> = keysBySchema.entries
        .sortedBy { it.key }
        .map { (schema, keys) -> KeyRegistryState(schema, keys.map { it.toString() }) }

    /**
     * Populates the registry from a previously persisted snapshot. Idempotent: already-
     * registered keys are not duplicated. Loaded keys are `String`; runtime `register(...)`
     * calls in the same process keep adding their original types — uniqueness is by
     * `Any.equals`, so `Long(1)` and `String("1")` stay distinct. Acceptable because reload
     * only happens at startup before any runtime registers fire.
     */
    fun restore(states: List<KeyRegistryState>) {
        states.forEach { state -> state.keys.forEach { key -> register(state.schemaName, key) } }
    }
}
