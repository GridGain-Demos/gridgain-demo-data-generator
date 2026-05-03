package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class KeyFieldDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `key defaults to false when omitted`() {
        val yaml = """
            name: id
            null_rate: 0.0
            value_source: { kind: sequence, start: 1, step: 1 }
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.key).isFalse()
    }

    @Test
    fun `key is read when present`() {
        val yaml = """
            name: id
            null_rate: 0.0
            key: true
            value_source: { kind: sequence, start: 1, step: 1 }
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.key).isTrue()
    }
}
