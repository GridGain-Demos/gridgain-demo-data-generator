package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.state.KeyRegistryState
import com.gridgain.demo.datagen.state.KeyType
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The keys this run has written, per schema, so a read operation can sample one that is known to
 * exist.
 *
 * **Thread-safe.** A scenario running with `concurrency > 1` has every worker thread registering
 * into one registry and sampling from it, so the plain `HashMap`/`ArrayList` this used to hold
 * would not merely lose keys — a racing resize can corrupt the table and spin forever.
 *
 * Dedupe is lock-free (a concurrent set); only the append to the sampleable list takes a write
 * lock, and sampling takes a shared read lock. The list is append-only, which is what lets reads
 * be so cheap. The cost of the lock is invisible next to the GridGain round trip it sits beside.
 */
class KeyRegistry {

    /**
     * One schema's keys. [seen] exists purely to make deduplication lock-free; [keys] is the
     * indexable copy that [sample] needs and is the one guarded.
     */
    private class SchemaKeys {
        private val seen: MutableSet<Any> = ConcurrentHashMap.newKeySet()
        private val lock = ReentrantReadWriteLock()
        private val keys: MutableList<Any> = ArrayList()

        fun add(key: Any) {
            // A key already present needs no lock at all — the common case once a run is warm.
            if (!seen.add(key)) return
            lock.write { keys.add(key) }
        }

        fun sample(random: Random): Any? = lock.read {
            if (keys.isEmpty()) null else keys[random.nextInt(keys.size)]
        }

        fun size(): Int = lock.read { keys.size }

        fun copy(): List<Any> = lock.read { ArrayList(keys) }
    }

    private val bySchema: ConcurrentHashMap<String, SchemaKeys> = ConcurrentHashMap()

    fun register(schemaName: String, key: Any) {
        bySchema.computeIfAbsent(schemaName) { SchemaKeys() }.add(key)
    }

    fun sample(schemaName: String, random: Random): Any? = bySchema[schemaName]?.sample(random)

    fun size(schemaName: String): Int = bySchema[schemaName]?.size() ?: 0

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
    fun snapshot(): List<KeyRegistryState> = bySchema.entries
        .sortedBy { it.key }
        .map { (schema, holder) -> schema to holder.copy() }
        // A holder is created immediately before its first key is appended, so a snapshot racing
        // that pair would otherwise reach inferKeyType's `keys.first()` with nothing in it. An
        // empty schema contributed no entry before this class was made concurrent either.
        .filter { (_, keys) -> keys.isNotEmpty() }
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
