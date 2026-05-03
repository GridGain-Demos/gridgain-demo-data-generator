package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8KvTargetReadTest {

    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test"

    @Test
    fun `read returns a value previously written`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val key = 2002L
            val parent = LinkedHashMap<String, Any?>().apply { put("id", key); put("note", "hello") }
            target.write(BusinessEvent(parentRow = parent, childrenBySchema = emptyMap()))
            val outcome = target.read(cacheName, key)
            assertThat(outcome.success).isTrue()
            assertThat(outcome.value).isNotNull
        }
    }

    @Test
    fun `read returns success-with-null for absent key`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val outcome = target.read(cacheName, key = -999_999L)
            assertThat(outcome.success).isTrue()
            assertThat(outcome.value).isNull()
        }
    }
}
