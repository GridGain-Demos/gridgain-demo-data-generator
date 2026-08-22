package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class JsonSchemaValidatorTest {

    @Test
    fun `valid v1 data yaml passes`() {
        val yaml = "schema_version: 1\n"
        assertThatCode { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml", version = 1) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `valid v1 ops yaml passes`() {
        val yaml = "schema_version: 1\n"
        assertThatCode { JsonSchemaValidator.validateOps(yaml, fileName = "ops.yaml", version = 1) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `wrong schema_version produces a remediation message naming the file`() {
        val yaml = "schema_version: 99\n"
        assertThatThrownBy { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml") }
            .isInstanceOf(MisconfigurationException::class.java)
            .satisfies({ ex ->
                assertThat(ex.message).contains("data.yaml")
                assertThat(ex.message).contains("schema_version")
            })
    }

    @Test
    fun `non-object root is rejected`() {
        val yaml = "- a\n- b\n"
        assertThatThrownBy { JsonSchemaValidator.validateData(yaml, fileName = "data.yaml") }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("data.yaml")
    }

    @Test
    fun `unknown schema version requested throws on lookup`() {
        assertThatThrownBy { JsonSchemaValidator.validateData("schema_version: 1\n", fileName = "data.yaml", version = 999) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("Unknown data schema version 999")
    }

    @Test
    fun `v7 rejects a top-level targets block`() {
        val yaml = """
            schema_version: 7
            targets:
              - name: t1
                kind: gg8-kv
                cluster_name: c1
            scenarios:
              - name: s1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 5 }
                read_ratio: 0.0
        """.trimIndent()

        assertThatThrownBy { JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("targets")
    }

    @Test
    fun `v7 rejects a scenario target field`() {
        val yaml = """
            schema_version: 7
            scenarios:
              - name: s1
                target: t1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 5 }
                read_ratio: 0.0
        """.trimIndent()

        assertThatThrownBy { JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("target")
    }

    @Test
    fun `v7 accepts a scenario with no target and no targets block`() {
        val yaml = """
            schema_version: 7
            scenarios:
              - name: s1
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 10 }
                duration: { kind: count, value: 5 }
                read_ratio: 0.0
        """.trimIndent()

        JsonSchemaValidator.validateOps(yaml, "ops.yaml", version = 7)  // must not throw
    }
}
