package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class MigrateV1toV2Test {

    @Test
    fun `from and to versions are 1 and 2`() {
        val m = MigrateV1toV2()
        assertThat(m.fromVersion).isEqualTo(1)
        assertThat(m.toVersion).isEqualTo(2)
        assertThat(m.description).contains("schemas")
    }

    @Test
    fun `injects empty schemas list when absent`() {
        val map: MutableMap<String, Any> = mutableMapOf("schema_version" to 1)
        val out = MigrateV1toV2().migrate(map)
        assertThat(out["schemas"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `leaves existing schemas list intact`() {
        val original = listOf(mapOf("name" to "customer"))
        val map: MutableMap<String, Any> = mutableMapOf(
            "schema_version" to 1,
            "schemas" to original,
        )
        val out = MigrateV1toV2().migrate(map)
        assertThat(out["schemas"]).isSameAs(original)
    }
}
