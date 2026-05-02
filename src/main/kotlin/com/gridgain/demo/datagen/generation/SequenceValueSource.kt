package com.gridgain.demo.datagen.generation

class SequenceValueSource(start: Long, private val step: Long) : ValueSource {
    private var nextValue: Long = start
    override fun next(ctx: GenerationContext): Any {
        val out = nextValue
        nextValue += step
        return out
    }
}
