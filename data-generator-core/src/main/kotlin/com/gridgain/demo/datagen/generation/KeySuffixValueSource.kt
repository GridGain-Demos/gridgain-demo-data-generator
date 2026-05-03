package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class KeySuffixValueSource(
    private val baseColumn: String,
    private val separator: String,
    private val length: Int,
    private val random: Random,
) : ValueSource {

    private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

    override fun next(ctx: GenerationContext): Any {
        val base = ctx.rowSoFar[baseColumn] ?: throw MisconfigurationException(
            "key-suffix value source references base_column '$baseColumn' " +
            "but no such column has been generated yet for this row. " +
            "Move '$baseColumn' to be declared earlier in the column list."
        )
        val suffix = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "$base$separator$suffix"
    }
}
