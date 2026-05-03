package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.Random
import kotlin.test.Test

class KeyRegistryTest {

    @Test
    fun `sample is null when registry is empty for that schema`() {
        val r = KeyRegistry()
        assertThat(r.sample("customer", Random(1L))).isNull()
    }

    @Test
    fun `register stores keys per schema`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        r.register("order", "abc")
        assertThat(r.size("customer")).isEqualTo(2)
        assertThat(r.size("order")).isEqualTo(1)
        assertThat(r.size("missing")).isEqualTo(0)
    }

    @Test
    fun `sample returns one of the registered keys`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        r.register("customer", 3L)
        val sampled = (1..100).map { r.sample("customer", Random(it.toLong())) }.toSet()
        assertThat(sampled).isSubsetOf(1L, 2L, 3L)
        assertThat(sampled).hasSizeGreaterThan(1)
    }

    @Test
    fun `register is idempotent for duplicate keys`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 1L)
        assertThat(r.size("customer")).isEqualTo(1)
    }
}
