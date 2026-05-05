package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class OtelSpecDeserializationTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    private val baseScenario = """
        scenarios:
          - name: s1
            target: t1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 1.0 }
            duration: { kind: count, value: 1 }
            read_ratio: 0.0
    """.trimIndent()

    @Test fun `defaults to NONE when otel block is omitted`() {
        val ops = mapper.readValue(
            "schema_version: 2\n$baseScenario",
            OpsConfig::class.java,
        )
        assertThat(ops.otel.exporter).isEqualTo(OtelExporter.NONE)
        assertThat(ops.otel.endpoint).isNull()
        assertThat(ops.otel.attributes).isEmpty()
    }

    @Test fun `parses otlp with endpoint and attributes`() {
        val yaml = """
            schema_version: 2
            otel:
              exporter: otlp
              endpoint: "http://otel:4318"
              attributes: { deployment.environment: dev }
        """.trimIndent() + "\n" + baseScenario
        val ops = mapper.readValue(yaml, OpsConfig::class.java)
        assertThat(ops.otel.exporter).isEqualTo(OtelExporter.OTLP)
        assertThat(ops.otel.endpoint).isEqualTo("http://otel:4318")
        assertThat(ops.otel.attributes).containsEntry("deployment.environment", "dev")
    }

    @Test fun `parses prometheus and none enum values`() {
        listOf("prometheus" to OtelExporter.PROMETHEUS, "none" to OtelExporter.NONE).forEach { (yaml, enum) ->
            val ops = mapper.readValue(
                "schema_version: 2\notel: { exporter: $yaml }\n$baseScenario",
                OpsConfig::class.java,
            )
            assertThat(ops.otel.exporter).isEqualTo(enum)
        }
    }
}
