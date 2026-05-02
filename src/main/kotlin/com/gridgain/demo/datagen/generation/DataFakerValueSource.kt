package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class DataFakerValueSource(private val expression: String) : ValueSource {
    override fun next(ctx: GenerationContext): Any =
        try {
            ctx.faker.expression(expression)
        } catch (e: Exception) {
            throw MisconfigurationException(
                "DataFaker expression '$expression' could not be evaluated: ${e.message}. " +
                "Verify the expression references a known DataFaker provider.",
                cause = e,
            )
        }
}
