package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = DataFakerSpec::class, name = "datafaker"),
    JsonSubTypes.Type(value = SequenceSpec::class, name = "sequence"),
    JsonSubTypes.Type(value = UniqueSpec::class, name = "unique"),
    JsonSubTypes.Type(value = WeightedChoiceSpec::class, name = "weighted-choice"),
    JsonSubTypes.Type(value = YamlDataSpec::class, name = "yaml-data"),
    JsonSubTypes.Type(value = ParentFkRefSpec::class, name = "parent-fk-ref"),
    JsonSubTypes.Type(value = KeySuffixSpec::class, name = "key-suffix"),
)
sealed class ValueSourceSpec

data class DataFakerSpec(val expression: String) : ValueSourceSpec()
data class SequenceSpec(val start: Long, val step: Long) : ValueSourceSpec()
data class UniqueSpec(val expression: String) : ValueSourceSpec()

data class WeightedChoice(val value: Any, val weight: Double)
data class WeightedChoiceSpec(val choices: List<WeightedChoice>) : ValueSourceSpec()

data class YamlDataSpec(val path: String, val key: String) : ValueSourceSpec()

data class CohortBucket(val share: Double, val multiplier: Int)

data class ParentFkRefSpec(
    @param:com.fasterxml.jackson.annotation.JsonProperty("parent_schema") val parentSchema: String,
    @param:com.fasterxml.jackson.annotation.JsonProperty("parent_column") val parentColumn: String,
    @param:com.fasterxml.jackson.annotation.JsonProperty("cohort_buckets") val cohortBuckets: List<CohortBucket>,
) : ValueSourceSpec()

data class KeySuffixSpec(
    @param:com.fasterxml.jackson.annotation.JsonProperty("base_column") val baseColumn: String,
    val separator: String,
    val length: Int,
) : ValueSourceSpec()
