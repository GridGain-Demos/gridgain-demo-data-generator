package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9KvTargetWriteTest {

    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test"

    @Test
    fun `write puts a parent row to the named table`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val parent = LinkedHashMap<String, Any?>().apply {
                put("id", 1001L); put("name", "Alice")
            }
            val event = BusinessEvent(parentSchemaName = tableName, parentRow = parent, childrenBySchema = emptyMap())
            val outcome = target.write(event)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "write failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
        }
    }
}
