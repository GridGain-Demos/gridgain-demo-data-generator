package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Per-schema list of keys emitted across all runs against this state file. Keys serialize as
 * `List<String>` for yaml-safety; `keyType` is the discriminator KeyRegistry.restore uses to
 * coerce them back to their original runtime type so a Long-keyed cache doesn't get sampled
 * with `String("1")` after a restart (closes F11).
 *
 * Per-schema homogeneity is the assumed invariant: a schema has exactly one key column
 * (`KeyColumnValidator`) and that column has one `ValueSourceSpec` whose runtime output type
 * is deterministic. `KeyRegistry.snapshot` enforces homogeneity at capture time.
 */
data class KeyRegistryState(
    @JsonProperty("schema_name") val schemaName: String,
    @JsonProperty("key_type") val keyType: KeyType,
    val keys: List<String>,
)

/**
 * Discriminator for [KeyRegistryState.keys] elements. Only LONG and STRING are wired today,
 * matching the realistic value-source outputs (SequenceSpec → Long; DataFakerSpec /
 * UniqueSpec / KeySuffixSpec / WeightedChoiceSpec / YamlDataSpec → String). Other key types
 * surface as `MisconfigurationException` at snapshot time with remediation.
 */
enum class KeyType { LONG, STRING }
