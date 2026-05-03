package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG8_CLUSTER_NAME", matches = ".+")
class Gg8KvTargetWriteTest {

    private val clusterName: String = System.getenv("DATAGEN_GG8_CLUSTER_NAME")!!
    private val cacheName: String = System.getenv("DATAGEN_GG8_TEST_CACHE") ?: "data_gen_test"

    @Test
    fun `write puts a parent row to the named cache`() {
        Gg8KvTarget(clusterName, keyColumnByName = mapOf(cacheName to "id")).use { target ->
            val parent = LinkedHashMap<String, Any?>().apply {
                put("id", 1001L); put("name", "Alice")
            }
            val event = BusinessEvent(parentSchemaName = cacheName, parentRow = parent, childrenBySchema = emptyMap())
            val outcome = target.write(event)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "write failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
        }
    }
}
