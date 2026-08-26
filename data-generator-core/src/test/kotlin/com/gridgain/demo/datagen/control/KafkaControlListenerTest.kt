package com.gridgain.demo.datagen.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaControlListenerTest {

    /** Builds a listener without touching Kafka — the consumer is only created inside the poll
     *  loop, so the parse/filter/dispatch path is directly exercisable. */
    private fun listener(
        runGroup: String,
        applied: MutableList<Double> = mutableListOf(),
        stops: MutableList<Unit> = mutableListOf(),
    ) = KafkaControlListener(
        bootstrapServers = "unused:9092",
        topic = "unused",
        runGroup = runGroup,
        runId = "instance-1",
        setRate = { applied += it },
        requestStop = { stops += Unit },
    )

    @Test
    fun `applies the rate from a set_rate command addressed to this run group`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle(
            """{"kind":"set_rate","runGroup":"grp-1","targetTpsPerInstance":250.0,"issuedAtMs":17}"""
        )

        assertEquals(listOf(250.0), applied)
    }

    @Test
    fun `ignores a command addressed to a different run group`() {
        val applied = mutableListOf<Double>()
        val stops = mutableListOf<Unit>()
        val l = listener("grp-1", applied, stops)

        l.handle("""{"kind":"set_rate","runGroup":"grp-2","targetTpsPerInstance":250.0,"issuedAtMs":17}""")
        l.handle("""{"kind":"stop","runGroup":"grp-2","issuedAtMs":17}""")

        assertTrue(applied.isEmpty(), "a fleet must only respond to its own group's commands")
        assertTrue(stops.isEmpty(), "one topic serves concurrent runs; a stop must not cross runs")
    }

    @Test
    fun `a zero rate is a valid pause command`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle(
            """{"kind":"set_rate","runGroup":"grp-1","targetTpsPerInstance":0.0,"issuedAtMs":1}"""
        )

        assertEquals(listOf(0.0), applied)
    }

    @Test
    fun `a stop command addressed to this run group requests a stop`() {
        val applied = mutableListOf<Double>()
        val stops = mutableListOf<Unit>()

        listener("grp-1", applied, stops).handle("""{"kind":"stop","runGroup":"grp-1","issuedAtMs":42}""")

        assertEquals(1, stops.size)
        assertTrue(applied.isEmpty(), "a stop must not touch the rate on its way out")
    }

    @Test
    fun `malformed json is ignored rather than killing the listener`() {
        val applied = mutableListOf<Double>()
        val stops = mutableListOf<Unit>()
        val l = listener("grp-1", applied, stops)

        l.handle("{not json at all")
        l.handle("")
        l.handle("""{"kind":"set_rate","runGroup":"grp-1"}""") // missing required fields
        l.handle("""{"kind":"stop","runGroup":"grp-1"}""")     // missing issuedAtMs

        assertTrue(applied.isEmpty())
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a set_rate missing its rate is rejected rather than defaulted to a pause`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle("""{"kind":"set_rate","runGroup":"grp-1","issuedAtMs":1}""")

        assertTrue(
            applied.isEmpty(),
            "Jackson would fill the missing Double with 0.0, silently pausing the whole fleet",
        )
    }

    @Test
    fun `a payload with no kind discriminator is rejected`() {
        val applied = mutableListOf<Double>()
        val stops = mutableListOf<Unit>()
        val l = listener("grp-1", applied, stops)

        // The pre-sealed-hierarchy flat message: valid before, deliberately rejected now.
        l.handle("""{"runGroup":"grp-1","targetTpsPerInstance":250.0,"issuedAtMs":17}""")

        assertTrue(applied.isEmpty(), "no backward compatibility: 'kind' is required")
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a payload of an unknown kind is rejected`() {
        val applied = mutableListOf<Double>()
        val stops = mutableListOf<Unit>()
        val l = listener("grp-1", applied, stops)

        l.handle("""{"kind":"self_destruct","runGroup":"grp-1","issuedAtMs":17}""")

        assertTrue(applied.isEmpty())
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a rejected rate does not propagate out of the listener`() {
        val l = KafkaControlListener(
            bootstrapServers = "unused:9092", topic = "unused",
            runGroup = "grp-1", runId = "instance-1",
            setRate = { require(it >= 0.0) { "negative" } },
            requestStop = {},
        )

        // A bad command must not take the control thread down with it.
        l.handle("""{"kind":"set_rate","runGroup":"grp-1","targetTpsPerInstance":-5.0,"issuedAtMs":1}""")
    }

    @Test
    fun `a failing stop callback does not propagate out of the listener`() {
        val l = KafkaControlListener(
            bootstrapServers = "unused:9092", topic = "unused",
            runGroup = "grp-1", runId = "instance-1",
            setRate = {},
            requestStop = { throw IllegalStateException("boom") },
        )

        l.handle("""{"kind":"stop","runGroup":"grp-1","issuedAtMs":1}""")
    }
}
