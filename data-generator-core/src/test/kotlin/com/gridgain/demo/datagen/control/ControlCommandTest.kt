package com.gridgain.demo.datagen.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * Locks the two wire payloads. They are quoted verbatim in the usage skill and are what a sender
 * (the demo UI) has to emit, so the shape is a published contract rather than an implementation
 * detail.
 */
class ControlCommandTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `a set_rate payload deserializes to a SetRateCommand`() {
        val json = """{"kind":"set_rate","runGroup":"20260809T101500Z","targetTpsPerInstance":250.0,""" +
            """"issuedAtMs":1754731200000}"""

        val command = mapper.readValue(json, ControlCommand::class.java)

        assertThat(command).isEqualTo(
            SetRateCommand(
                runGroup = "20260809T101500Z",
                targetTpsPerInstance = 250.0,
                issuedAtMs = 1754731200000L,
            )
        )
    }

    @Test
    fun `a stop payload deserializes to a StopCommand`() {
        val json = """{"kind":"stop","runGroup":"20260809T101500Z","issuedAtMs":1754731200000}"""

        val command = mapper.readValue(json, ControlCommand::class.java)

        assertThat(command).isEqualTo(
            StopCommand(runGroup = "20260809T101500Z", issuedAtMs = 1754731200000L)
        )
    }

    @Test
    fun `serializing writes the kind discriminator alongside camelCase fields`() {
        val setRate = mapper.writeValueAsString(
            SetRateCommand(runGroup = "g", targetTpsPerInstance = 12.5, issuedAtMs = 7L) as ControlCommand
        )
        val stop = mapper.writeValueAsString(StopCommand(runGroup = "g", issuedAtMs = 7L) as ControlCommand)

        assertThat(mapper.readTree(setRate).get("kind").asText()).isEqualTo("set_rate")
        assertThat(mapper.readTree(setRate).get("targetTpsPerInstance").asDouble()).isEqualTo(12.5)
        assertThat(mapper.readTree(stop).get("kind").asText()).isEqualTo("stop")
        assertThat(mapper.readTree(stop).has("targetTpsPerInstance"))
            .describedAs("a stop carries no rate: a message meaning both would mean neither")
            .isFalse()
    }
}
