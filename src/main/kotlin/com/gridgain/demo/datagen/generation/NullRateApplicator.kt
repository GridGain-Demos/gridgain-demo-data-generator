package com.gridgain.demo.datagen.generation

import java.util.Random

class NullRateApplicator(
    private val inner: ValueSource,
    private val nullRate: Double,
    private val random: Random,
) : ValueSource {
    override fun next(ctx: GenerationContext): Any? =
        if (nullRate > 0.0 && random.nextDouble() < nullRate) null else inner.next(ctx)
}
