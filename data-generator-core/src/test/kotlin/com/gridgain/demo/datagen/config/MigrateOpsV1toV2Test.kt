package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateOpsV1toV2Test {

    @Test
    fun `from and to versions are 1 and 2`() {
        val m = MigrateOpsV1toV2()
        assertThat(m.fromVersion).isEqualTo(1)
        assertThat(m.toVersion).isEqualTo(2)
        assertThat(m.description).contains("scenarios")
    }

    @Test
    fun `injects empty scenarios list when absent`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 1)
        val out = MigrateOpsV1toV2().migrate(map)
        assertThat(out["scenarios"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `leaves existing scenarios list intact`() {
        val original = listOf(mapOf("name" to "alpha"))
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 1, "scenarios" to original
        )
        val out = MigrateOpsV1toV2().migrate(map)
        assertThat(out["scenarios"]).isSameAs(original)
    }
}
