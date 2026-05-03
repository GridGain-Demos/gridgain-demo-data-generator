package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ProvisioningModeDeserializationTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper
    private val base = """
        name: s1
        target: t1
        root_schemas: [customer]
        rate: { kind: constant, ops_per_second: 1.0 }
        duration: { kind: count, value: 1 }
        read_ratio: 0.0
    """.trimIndent()

    @Test fun `defaults to SKIP when omitted`() {
        val s: ScenarioSpec = mapper.readValue(base, ScenarioSpec::class.java)
        assertThat(s.provisioning).isEqualTo(ProvisioningMode.SKIP)
    }
    @Test fun `parses skip`() = check("skip", ProvisioningMode.SKIP)
    @Test fun `parses emit`() = check("emit", ProvisioningMode.EMIT)
    @Test fun `parses apply`() = check("apply", ProvisioningMode.APPLY)

    private fun check(yaml: String, expected: ProvisioningMode) {
        val s: ScenarioSpec = mapper.readValue("$base\nprovisioning: $yaml", ScenarioSpec::class.java)
        assertThat(s.provisioning).isEqualTo(expected)
    }
}
