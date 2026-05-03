package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class InMemoryTargetTest {

    private fun event(id: Long): BusinessEvent {
        val parent = LinkedHashMap<String, Any?>().apply { put("id", id) }
        return BusinessEvent(parentRow = parent, childrenBySchema = emptyMap())
    }

    @Test
    fun `records each successful write`() {
        val t = InMemoryTarget()
        t.write(event(1L))
        t.write(event(2L))
        assertThat(t.writes).hasSize(2)
        assertThat(t.writes[0].parentRow["id"]).isEqualTo(1L)
    }

    @Test
    fun `supports reads is true and supports transactions is false`() {
        val t = InMemoryTarget()
        assertThat(t.supportsReads).isTrue()
        assertThat(t.supportsTransactions).isFalse()
    }

    @Test
    fun `write returns a successful WriteOutcome`() {
        val outcome = InMemoryTarget().write(event(1L))
        assertThat(outcome.success).isTrue()
        assertThat(outcome.error).isNull()
    }

    @Test
    fun `records each read call`() {
        val t = InMemoryTarget()
        val r = t.read(cacheName = "customer", key = 42L)
        assertThat(r.success).isTrue()
        assertThat(r.value).isEqualTo("in-memory-stub")
        assertThat(t.reads).hasSize(1)
        assertThat(t.reads[0].cacheName).isEqualTo("customer")
        assertThat(t.reads[0].key).isEqualTo(42L)
    }
}
