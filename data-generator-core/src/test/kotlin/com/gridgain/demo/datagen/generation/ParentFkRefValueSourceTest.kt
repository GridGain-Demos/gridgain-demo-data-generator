package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ParentFkRefValueSourceTest {

    @Test
    fun `returns the parent column value`() {
        val ctx = GenerationContext(
            faker = Faker(),
            parentRow = mapOf("id" to 42L, "name" to "Alice"),
        )
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThat(s.next(ctx)).isEqualTo(42L)
    }

    @Test
    fun `null parentRow is rejected with remediation`() {
        val ctx = GenerationContext(faker = Faker())
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("customer")
            .hasMessageContaining("parent row")
    }

    @Test
    fun `parentRow missing the named column is rejected`() {
        val ctx = GenerationContext(
            faker = Faker(),
            parentRow = mapOf("name" to "Alice"),
        )
        val s = ParentFkRefValueSource(parentSchema = "customer", parentColumn = "id")
        assertThatThrownBy { s.next(ctx) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("customer.id")
    }
}
