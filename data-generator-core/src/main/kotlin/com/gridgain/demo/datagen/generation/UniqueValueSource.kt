package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class UniqueValueSource(
    private val expression: String,
    private val maxRetries: Int,
) : ValueSource {

    private val emitted: MutableSet<Any?> = mutableSetOf()
    private val inner = DataFakerValueSource(expression)

    override fun next(ctx: GenerationContext): Any {
        repeat(maxRetries) {
            val candidate = inner.next(ctx)
            if (emitted.add(candidate)) return candidate
        }
        throw MisconfigurationException(
            "Unique value source for expression '$expression' exhausted after $maxRetries retries " +
            "(${emitted.size} unique values already emitted). " +
            "Either widen the expression's value space or stop demanding uniqueness for this column."
        )
    }
}
