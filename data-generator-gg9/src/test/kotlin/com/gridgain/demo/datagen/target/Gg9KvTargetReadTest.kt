package com.gridgain.demo.datagen.target

import com.gridgain.demo.datagen.generation.BusinessEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test

@EnabledIfEnvironmentVariable(named = "DATAGEN_GG9_CLUSTER_NAME", matches = ".+")
class Gg9KvTargetReadTest {

    private val clusterName: String = System.getenv("DATAGEN_GG9_CLUSTER_NAME")!!
    private val tableName: String = System.getenv("DATAGEN_GG9_TEST_TABLE") ?: "data_gen_test"

    @Test
    fun `read returns a value previously written`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val key = 2002L
            val parent = LinkedHashMap<String, Any?>().apply { put("id", key); put("name", "hello") }
            val writeOutcome = target.write(BusinessEvent(parentRow = parent, childrenBySchema = emptyMap()))
            if (!writeOutcome.success) writeOutcome.error?.printStackTrace()
            val outcome = target.read(tableName, key)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "read failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
            assertThat(outcome.value).isNotNull
        }
    }

    @Test
    fun `read returns success-with-null for absent key`() {
        Gg9KvTarget(clusterName, keyColumnByName = mapOf(tableName to "id")).use { target ->
            val outcome = target.read(tableName, key = -999_999L)
            if (!outcome.success) outcome.error?.printStackTrace()
            assertThat(outcome.success)
                .withFailMessage { "read failed: ${outcome.error?.message ?: "no error captured"}" }
                .isTrue()
            assertThat(outcome.value).isNull()
        }
    }
}
