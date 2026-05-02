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
)
sealed class ValueSourceSpec

data class DataFakerSpec(val expression: String) : ValueSourceSpec()
data class SequenceSpec(val start: Long, val step: Long) : ValueSourceSpec()
data class UniqueSpec(val expression: String) : ValueSourceSpec()

data class WeightedChoice(val value: Any, val weight: Double)
data class WeightedChoiceSpec(val choices: List<WeightedChoice>) : ValueSourceSpec()

data class YamlDataSpec(val path: String, val key: String) : ValueSourceSpec()
