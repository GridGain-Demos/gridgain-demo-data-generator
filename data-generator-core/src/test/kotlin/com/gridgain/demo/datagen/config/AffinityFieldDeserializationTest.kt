package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AffinityFieldDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `affinity defaults to false when omitted`() {
        val yaml = """
            name: id
            null_rate: 0.0
            value_source:
              kind: sequence
              start: 1
              step: 1
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.affinity).isFalse()
    }

    @Test
    fun `affinity is read when present`() {
        val yaml = """
            name: id
            null_rate: 0.0
            affinity: true
            value_source:
              kind: sequence
              start: 1
              step: 1
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.affinity).isTrue()
    }
}
