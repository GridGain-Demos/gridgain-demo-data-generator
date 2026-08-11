package com.gridgain.demo.datagen.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaControlListenerTest {

    /** Builds a listener without touching Kafka — the consumer is only created inside the poll
     *  loop, so the parse/filter/apply path is directly exercisable. */
    private fun listener(runGroup: String, applied: MutableList<Double>) =
        KafkaControlListener(
            bootstrapServers = "unused:9092",
            topic = "unused",
            runGroup = runGroup,
            runId = "instance-1",
            setRate = { applied += it },
        )

    @Test
    fun `applies the rate from a command addressed to this run group`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle(
            """{"runGroup":"grp-1","targetTpsPerInstance":250.0,"issuedAtMs":17}"""
        )

        assertEquals(listOf(250.0), applied)
    }

    @Test
    fun `ignores a command addressed to a different run group`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle(
            """{"runGroup":"grp-2","targetTpsPerInstance":250.0,"issuedAtMs":17}"""
        )

        assertTrue(applied.isEmpty(), "a fleet must only respond to its own group's commands")
    }

    @Test
    fun `a zero rate is a valid pause command`() {
        val applied = mutableListOf<Double>()

        listener("grp-1", applied).handle(
            """{"runGroup":"grp-1","targetTpsPerInstance":0.0,"issuedAtMs":1}"""
        )

        assertEquals(listOf(0.0), applied)
    }

    @Test
    fun `malformed json is ignored rather than killing the listener`() {
        val applied = mutableListOf<Double>()
        val l = listener("grp-1", applied)

        l.handle("{not json at all")
        l.handle("")
        l.handle("""{"runGroup":"grp-1"}""") // missing required field

        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a rejected rate does not propagate out of the listener`() {
        val l = KafkaControlListener(
            bootstrapServers = "unused:9092", topic = "unused",
            runGroup = "grp-1", runId = "instance-1",
            setRate = { require(it >= 0.0) { "negative" } },
        )

        // A bad command must not take the control thread down with it.
        l.handle("""{"runGroup":"grp-1","targetTpsPerInstance":-5.0,"issuedAtMs":1}""")
    }
}
