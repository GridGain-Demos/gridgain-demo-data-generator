package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Gg8KvTargetSpec::class, name = "gg8-kv"),
    JsonSubTypes.Type(value = Gg9KvTargetSpec::class, name = "gg9-kv"),
)
sealed class TargetSpec {
    abstract val name: String
}

data class Gg8KvTargetSpec(
    override val name: String,
    @JsonProperty("cluster_name") val clusterName: String,
) : TargetSpec()

data class Gg9KvTargetSpec(
    override val name: String,
    @JsonProperty("cluster_name") val clusterName: String,
) : TargetSpec()
