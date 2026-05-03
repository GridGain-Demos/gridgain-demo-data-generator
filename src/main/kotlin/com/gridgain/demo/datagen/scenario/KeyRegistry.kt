package com.gridgain.demo.datagen.scenario

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
}
