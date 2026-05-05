package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.state.KeyRegistryState
import com.gridgain.demo.datagen.state.KeyType
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

    @Test fun `snapshot returns empty list when registry is empty`() {
        assertThat(KeyRegistry().snapshot()).isEmpty()
    }

    @Test fun `snapshot returns one entry per registered schema`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        r.register("order", "o-100")
        val snap = r.snapshot()
        assertThat(snap).hasSize(2)
        assertThat(snap.first { it.schemaName == "customer" }.keys)
            .containsExactly("1", "2")
        assertThat(snap.first { it.schemaName == "order" }.keys)
            .containsExactly("o-100")
    }

    @Test fun `restore populates registry from a saved list (STRING)`() {
        val r = KeyRegistry()
        r.restore(listOf(KeyRegistryState("customer", KeyType.STRING, listOf("1", "2", "3"))))
        assertThat(r.size("customer")).isEqualTo(3)
        val rng = Random(0L)
        assertThat(r.sample("customer", rng)).isIn("1", "2", "3")
    }

    @Test fun `restore does not duplicate already-registered keys`() {
        val r = KeyRegistry()
        r.register("customer", "1")
        r.restore(listOf(KeyRegistryState("customer", KeyType.STRING, listOf("1", "2"))))
        assertThat(r.size("customer")).isEqualTo(2)
    }

    @Test fun `snapshot infers LONG keyType for Long keys`() {
        val r = KeyRegistry()
        r.register("customer", 1L)
        r.register("customer", 2L)
        val snap = r.snapshot()
        assertThat(snap).hasSize(1)
        assertThat(snap[0].keyType).isEqualTo(KeyType.LONG)
        assertThat(snap[0].keys).containsExactly("1", "2")
    }

    @Test fun `snapshot infers STRING keyType for String keys`() {
        val r = KeyRegistry()
        r.register("order", "abc")
        val snap = r.snapshot()
        assertThat(snap[0].keyType).isEqualTo(KeyType.STRING)
        assertThat(snap[0].keys).containsExactly("abc")
    }

    @Test fun `restore coerces LONG keys back to Long so sample matches the original type`() {
        val r = KeyRegistry()
        r.restore(listOf(KeyRegistryState("customer", KeyType.LONG, listOf("1", "2", "3"))))
        val sampled = r.sample("customer", Random(0L))
        assertThat(sampled).isInstanceOf(java.lang.Long::class.java)
        assertThat(sampled).isIn(1L, 2L, 3L)
    }

    @Test fun `snapshot rejects unsupported key types with remediation`() {
        val r = KeyRegistry()
        r.register("widget", 3.14) // Double — not wired
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.gridgain.demo.datagen.errors.MisconfigurationException::class.java,
        ) { r.snapshot() }
        assertThat(ex.message).contains("widget").contains("Long").contains("String")
    }

    @Test fun `restore fails fast with remediation when LONG key is unparseable`() {
        val r = KeyRegistry()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.gridgain.demo.datagen.errors.MisconfigurationException::class.java,
        ) {
            r.restore(listOf(KeyRegistryState("customer", KeyType.LONG, listOf("not-a-number"))))
        }
        assertThat(ex.message).contains("not-a-number").contains("tear down")
    }
}
