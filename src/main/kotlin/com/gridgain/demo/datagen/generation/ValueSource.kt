package com.gridgain.demo.datagen.generation

import net.datafaker.Faker

interface ValueSource {
    fun next(ctx: GenerationContext): Any?
}

/**
 * Per-row context. `rowSoFar` is mutable — RowGenerator populates it as it walks
 * columns left-to-right so later columns can reference earlier ones (key-suffix).
 * `parentRow` is the parent row when this row is being generated as a child of
 * another schema (parent-fk-ref); null otherwise.
 */
data class GenerationContext(
    val faker: Faker,
    val rowSoFar: MutableMap<String, Any?> = mutableMapOf(),
    val parentRow: Map<String, Any?>? = null,
)
