package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.state.KeyRegistryState
import com.gridgain.demo.datagen.state.KeyType
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
     * Each entry carries a [KeyType] discriminator so `restore` can coerce yaml-stringified
     * keys back to their original runtime type — `Long(1)` round-trips as `Long(1)`, not
     * `String("1")`. Schemas are deterministically ordered for stable yaml output.
     *
     * Per-schema homogeneity is required: every key for a given schema must share a runtime
     * type. Heterogeneity throws [MisconfigurationException]; in practice this can't happen
     * because `KeyColumnValidator` enforces one key column per schema and a column's
     * `ValueSourceSpec` produces a single deterministic type.
     */
    fun snapshot(): List<KeyRegistryState> = keysBySchema.entries
        .sortedBy { it.key }
        .map { (schema, keys) -> KeyRegistryState(schema, inferKeyType(schema, keys), keys.map { it.toString() }) }

    private fun inferKeyType(schemaName: String, keys: List<Any>): KeyType {
        val first = keys.first()
        val type = when (first) {
            is Long -> KeyType.LONG
            is String -> KeyType.STRING
            else -> throw MisconfigurationException(
                "KeyRegistry.snapshot does not support keys of runtime type " +
                "'${first.javaClass.name}' (schema '$schemaName'). Only Long and String are " +
                "wired in state.yaml today. Either change the key column's value source to " +
                "a Long- or String-producing type, or extend KeyType + KeyRegistry to cover " +
                "this type."
            )
        }
        keys.forEach { k ->
            val mismatched = (type == KeyType.LONG && k !is Long) || (type == KeyType.STRING && k !is String)
            if (mismatched) throw MisconfigurationException(
                "KeyRegistry detected mixed key types for schema '$schemaName': first key is " +
                "$type but key '$k' is ${k.javaClass.name}. State serialization requires per-" +
                "schema homogeneity. KeyColumnValidator + value-source determinism should " +
                "have prevented this — please report."
            )
        }
        return type
    }

    /**
     * Populates the registry from a previously persisted snapshot. Each [KeyRegistryState]'s
     * `keyType` drives string→runtime coercion: LONG → `String.toLong()`; STRING → as-is.
     * Idempotent — already-registered keys are not duplicated.
     *
     * Order: `restore` runs at startup before any runtime `register(...)` fires, so once it
     * returns, every persisted key is in its original runtime type. `Any.equals` then keeps
     * subsequent runtime registers distinct from any string-stringified persisted siblings.
     */
    fun restore(states: List<KeyRegistryState>) {
        states.forEach { state ->
            state.keys.forEach { raw ->
                val typed: Any = when (state.keyType) {
                    KeyType.LONG -> raw.toLongOrNull() ?: throw MisconfigurationException(
                        "state.yaml has schema '${state.schemaName}' with key_type=LONG but " +
                        "key '$raw' does not parse as a Long. Either repair the file or tear " +
                        "down state.yaml and rerun from clean state."
                    )
                    KeyType.STRING -> raw
                }
                register(state.schemaName, typed)
            }
        }
    }
}
