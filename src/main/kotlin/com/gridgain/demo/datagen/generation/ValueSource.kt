package com.gridgain.demo.datagen.generation

import net.datafaker.Faker

/**
 * The runtime contract for value generators. One instance per (schema, column).
 * Plan 3 will extend GenerationContext with rowSoFar/parentRow/etc; this contract is stable.
 */
interface ValueSource {
    fun next(ctx: GenerationContext): Any?
}

/**
 * Shared per-row context. Plan 2 only carries a Faker. Plan 3 will add cross-column and
 * cross-row fields without changing the interface.
 */
data class GenerationContext(
    val faker: Faker,
)
