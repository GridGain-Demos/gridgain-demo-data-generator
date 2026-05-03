package com.gridgain.demo.datagen.runtime

import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RunId {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val RNG = SecureRandom()

    fun generate(clock: Clock = Clock.systemUTC()): String {
        val timestamp = FORMAT.format(clock.instant())
        val suffix = (1..6).map { ALPHABET[RNG.nextInt(ALPHABET.length)] }.joinToString("")
        return "$timestamp-$suffix"
    }
}
