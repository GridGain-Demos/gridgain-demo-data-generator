package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg9KvTargetSpecDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `gg9-kv target deserializes into Gg9KvTargetSpec`() {
        val yaml = """
            kind: gg9-kv
            name: gg9-trip
            cluster_name: trip-cluster-9
        """.trimIndent()
        val target: TargetSpec = mapper.readValue(yaml, TargetSpec::class.java)
        assertThat(target).isInstanceOf(Gg9KvTargetSpec::class.java)
        target as Gg9KvTargetSpec
        assertThat(target.name).isEqualTo("gg9-trip")
        assertThat(target.clusterName).isEqualTo("trip-cluster-9")
    }

    @Test
    fun `gg8-kv target still deserializes into Gg8KvTargetSpec (regression guard)`() {
        val yaml = """
            kind: gg8-kv
            name: gg8-trip
            cluster_name: trip-cluster-8
        """.trimIndent()
        val target: TargetSpec = mapper.readValue(yaml, TargetSpec::class.java)
        assertThat(target).isInstanceOf(Gg8KvTargetSpec::class.java)
    }
}
