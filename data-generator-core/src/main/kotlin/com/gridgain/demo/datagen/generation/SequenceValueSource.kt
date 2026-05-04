package com.gridgain.demo.datagen.generation

class SequenceValueSource(
    start: Long,
    private val step: Long,
    initialPosition: Long? = null,
) : ValueSource {

    private var nextValue: Long = initialPosition ?: start

    /** The value that will be returned by the next call to `next()`. Used by `ValueSourceFactory`
     *  to capture sequence cursors at end of run for `state.yaml`. */
    val currentNext: Long get() = nextValue

    override fun next(ctx: GenerationContext): Any {
        val out = nextValue
        nextValue += step
        return out
    }
}
