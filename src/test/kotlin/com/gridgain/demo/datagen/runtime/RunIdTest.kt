package com.gridgain.demo.datagen.runtime

import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class RunIdTest {

    @Test
    fun `run id starts with utc date in YYYYMMDD-HHMMSS form`() {
        val fixed = Clock.fixed(Instant.parse("2026-05-02T13:14:15Z"), ZoneOffset.UTC)
        val id = RunId.generate(fixed)
        assertThat(id).startsWith("20260502-131415-")
    }

    @Test
    fun `run id has a random suffix and is filesystem-safe`() {
        val a = RunId.generate()
        val b = RunId.generate()
        assertThat(a).isNotEqualTo(b)
        assertThat(a).matches("^[0-9]{8}-[0-9]{6}-[a-z0-9]{6}$")
    }

    @Test
    fun `run ids generated in time order sort lexicographically`() {
        val earlier = Clock.fixed(Instant.parse("2026-05-02T13:14:15Z"), ZoneOffset.UTC)
        val later = Clock.fixed(Instant.parse("2026-05-02T13:14:16Z"), ZoneOffset.UTC)
        val a = RunId.generate(earlier)
        val b = RunId.generate(later)
        assertThat(a < b).isTrue()
    }
}
