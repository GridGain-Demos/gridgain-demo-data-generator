package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import kotlin.test.Test

class CrossElementValidatorTest {

    @Test
    fun `default validator accepts a v1 envelope`() {
        val data = DataConfig(schemaVersion = 1, schemas = emptyList())
        val ops = OpsConfig(schemaVersion = 1)
        val result = DefaultCrossElementValidator().validate(data, ops)
        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `validator returns a CrossElementValidationResult`() {
        val data = DataConfig(schemaVersion = 1, schemas = emptyList())
        val ops = OpsConfig(schemaVersion = 1)
        assertThatCode { DefaultCrossElementValidator().validate(data, ops) }
            .doesNotThrowAnyException()
    }
}
