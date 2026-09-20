package com.gridgain.demo.datagen.metrics

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The snapshot now reports the *shape* of the process that produced it: how many worker threads it
 * runs, and which slice of the key space it owns.
 *
 * Both were previously invisible to every consumer, which is why the demo UI's Load page could only
 * guess at a fleet's ceiling — it assumed one operation in flight per instance, a statement that
 * stopped being true when ops v8 introduced `concurrency`. It is also why a fleet whose instances
 * disagree about the key-space split could not be detected anywhere a human would look.
 *
 * ### Why one nested object rather than two flat integers
 * A missing *reference* type fails Jackson deserialization, which is the behaviour a consumer
 * wants: an older generator's snapshot is recognisably incomplete. A missing JVM *primitive* is
 * silently filled with 0 by Kotlin's value instantiator — so flat `Int` fields would read as
 * "concurrency 0" rather than "this generator predates the field", and a ceiling computed from
 * that would be silently wrong instead of absent.
 */
class InstanceShapeTest {

    private val mapper = jacksonObjectMapper()

    private fun snapshot(shape: InstanceShape) = MetricsSnapshot(
        updatedAtMs = 1L, observedTps = 1.0, avgLatencyMs = 1.0, totalOps = 1L, errorCount = 0L,
        runAvgTps = 1.0, runAvgLatencyMs = 1.0, runLatencyHistogram = "", targetTps = 1.0,
        runGroup = "grp", runId = "run", active = true, shape = shape,
    )

    @Test
    fun `a striped process reports its slice and its thread count`() {
        val s = snapshot(InstanceShape(concurrency = 32, stripe = StripeRef(index = 1, count = 2)))

        assertEquals(32, s.shape.concurrency)
        assertEquals(1, s.shape.stripe?.index)
        assertEquals(2, s.shape.stripe?.count)
    }

    @Test
    fun `an unstriped process reports a null stripe, not a synthetic one-of-one`() {
        // Mirrors PartitionStripe? itself, which is null rather than (0,1) so that nothing
        // announces striping that is not happening.
        val s = snapshot(InstanceShape(concurrency = 4, stripe = null))

        assertNull(s.shape.stripe)
        assertEquals(4, s.shape.concurrency)
    }

    @Test
    fun `the JSON shape is the contract, so it is asserted literally`() {
        val json = mapper.writeValueAsString(
            snapshot(InstanceShape(concurrency = 32, stripe = StripeRef(index = 1, count = 2)))
        )

        assertTrue(
            json.contains("\"shape\":{\"concurrency\":32,\"stripe\":{\"index\":1,\"count\":2}}"),
            "the demo UI mirrors this JSON by hand; got: $json",
        )
    }

    @Test
    fun `an unstriped process serialises stripe as null rather than omitting it`() {
        val json = mapper.writeValueAsString(snapshot(InstanceShape(concurrency = 1, stripe = null)))

        assertTrue(
            json.contains("\"shape\":{\"concurrency\":1,\"stripe\":null}"),
            "an explicit null says 'no stripe'; an absent key would be indistinguishable from an " +
                "older generator that cannot report one at all. Got: $json",
        )
    }

    @Test
    fun `a snapshot round-trips through JSON unchanged`() {
        val original = snapshot(InstanceShape(concurrency = 8, stripe = StripeRef(3, 4)))

        assertEquals(original, mapper.readValue<MetricsSnapshot>(mapper.writeValueAsString(original)))
    }
}
