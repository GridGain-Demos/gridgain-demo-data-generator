package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * How the scenario chooses its keys (ops v10+).
 *
 * Discriminated rather than "a size, optionally" because the two modes are genuinely different
 * behaviours, not one behaviour with a missing number — and because a nullable size would make the
 * unbounded case the silent one. [MigrateOpsV9toV10] writes `kind: unbounded` into every existing
 * scenario, so an upgraded file behaves exactly as it did before.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = UnboundedKeySpaceSpec::class, name = "unbounded"),
    JsonSubTypes.Type(value = BoundedKeySpaceSpec::class, name = "bounded"),
)
sealed class KeySpaceSpec

/**
 * The behaviour every scenario had before v10: write keys come from the key column's own
 * `value_source` (in practice an unbounded `sequence`), and reads sample whatever this process has
 * registered during this run.
 *
 * Fine for a demo that wants an ever-growing dataset. Unusable for measurement, because no two runs
 * address the same rows and a read can only ever find something this process just wrote.
 */
class UnboundedKeySpaceSpec : KeySpaceSpec() {
    override fun equals(other: Any?) = other is UnboundedKeySpaceSpec
    override fun hashCode() = 0
}

/**
 * A fixed domain of [size] keys that both reads and writes draw from — the equivalent of Yardstick's
 * `-r/--range`.
 *
 * ### This overrides the key column's value source, deliberately
 *
 * In bounded mode the root schema's key column is generated from the key space, not from its
 * `value_source`. That is the whole point: a put and a get have to be able to name the same row. A
 * `sequence` key column and a bounded read range would guarantee the opposite.
 *
 * ### Two consequences worth stating before someone is surprised by them
 *
 * **Writes overwrite.** Drawing randomly from a fixed domain means collisions, by construction —
 * which is exactly what a put benchmark measures. It also means the per-worker key striping that
 * prevents duplicate primary keys in unbounded mode does not apply here, and every instance of a
 * fleet addresses the same space. That is correct for a benchmark, where all drivers hit one range.
 *
 * **A get-only run must be preceded by a put run.** Nothing pre-populates the space, so reads
 * against a space that was never filled all miss. `read_miss_count` in `result.yaml` is the signal;
 * check it before quoting any read figure.
 */
data class BoundedKeySpaceSpec(
    val size: Long,
    val distribution: KeyDistribution,
) : KeySpaceSpec()

enum class KeyDistribution {
    /** Every key equally likely. The honest default, and a cache's worst case. */
    @JsonProperty("uniform") UNIFORM,

    /** A hot head and a long cold tail — the shape real access patterns take. */
    @JsonProperty("zipfian") ZIPFIAN,

    /** Skewed toward the most recently written keys: a feed, a ledger tail, an event stream. */
    @JsonProperty("latest") LATEST,
}
