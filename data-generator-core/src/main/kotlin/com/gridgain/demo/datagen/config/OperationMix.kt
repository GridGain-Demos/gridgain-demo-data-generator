package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

/**
 * What proportion of a scenario's operations are of each kind (ops v10+), replacing `read_ratio`.
 *
 * A single `read_ratio` scalar could say only "some fraction are reads". It could not express a
 * third operation at all, which is why [putGet] — a get and a put against the **same key**, the
 * operation `PutGetBenchmark` measures — simply did not exist: interleaving independent gets and
 * puts is a different workload, and a cache behaves differently under it.
 *
 * All three weights are required and carry no Kotlin default, per the comprehensive-configuration
 * policy. [MigrateOpsV9toV10] writes all three explicitly from the old `read_ratio`, so an upgraded
 * file runs exactly the mix it ran before.
 */
data class OperationMix(
    val put: Double,
    val get: Double,
    @JsonProperty("put_get") val putGet: Double,
) {
    init {
        listOf("put" to put, "get" to get, "put_get" to putGet).forEach { (name, weight) ->
            if (weight < 0.0 || weight.isNaN()) {
                throw MisconfigurationException(
                    "operations.$name must be zero or positive; got $weight. The three weights are " +
                        "proportions of the scenario's operations and must sum to 1.0."
                )
            }
        }
        val total = put + get + putGet
        if (kotlin.math.abs(total - 1.0) > TOLERANCE) {
            throw MisconfigurationException(
                "operations weights sum to ${"%.4f".format(total)} but must sum to 1.0 " +
                    "(within $TOLERANCE): put=$put, get=$get, put_get=$putGet. They are " +
                    "proportions of one scenario's operations, not independent rates — adjust them " +
                    "so they total 1.0."
            )
        }
    }

    /**
     * Choose one operation.
     *
     * Cumulative rather than a `when` over thresholds so that adding a fourth operation later is a
     * list entry, not a re-derivation of every boundary. The order is fixed so that a given seed
     * reproduces a given sequence — a benchmark that cannot be repeated is an anecdote.
     */
    fun choose(random: Random): OperationKind {
        val roll = random.nextDouble()
        var cumulative = put
        if (roll < cumulative) return OperationKind.PUT
        cumulative += get
        if (roll < cumulative) return OperationKind.GET
        // Falls through on floating-point slack at the top of the range as well as on a genuine
        // put_get draw. Both should land here: put_get is the last weight, so it owns the remainder.
        return OperationKind.PUT_GET
    }

    private companion object {
        const val TOLERANCE = 0.001
    }
}

enum class OperationKind(val metricLabel: String) {
    PUT("put"),
    GET("get"),

    /**
     * A get followed by a put **against the same key**, counted as one operation.
     *
     * Distinct from a `get` and a `put` that happen to follow each other: the read-then-write pair
     * is the read-modify-write a real application performs, it touches one partition twice in
     * succession, and its latency is the sum of both round trips. That is what
     * `PutGetBenchmark` measures and what `read_ratio` could never express.
     */
    PUT_GET("put_get"),
}
