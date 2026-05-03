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
}
