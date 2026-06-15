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

    @Test
    fun `current ops schema accepts a gg9-kv target`() {
        val yaml = """
            schema_version: $CURRENT_OPS_SCHEMA_VERSION
            targets:
              - { kind: gg9-kv, name: gg9-trip, cluster_name: trip-cluster-9 }
            scenarios:
              - name: s
                target: gg9-trip
                root_schemas: [customer]
                rate: { kind: constant, ops_per_second: 50 }
                duration: { kind: count, value: 100 }
                transaction_scope: business_event
                read_ratio: 0.0
        """.trimIndent()
        // No exception means the schema accepted the document.
        JsonSchemaValidator.validateOps(yaml, fileName = "ops.yaml")
    }
}
