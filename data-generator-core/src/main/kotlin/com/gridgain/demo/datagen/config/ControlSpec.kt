package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Optional runtime control channel (v5+): a Kafka topic the generator consumes commands from, so an
 * external driver such as the demo UI can raise and lower the load while the run is in flight.
 * Absent (`control:` omitted) means the scenario's configured rate schedule is the only thing
 * pacing the run — which is the behaviour every run had before v5.
 *
 * Kept separate from [MetricsSpec] rather than folded into it: telemetry out and commands in are
 * independent concerns, and a deployment may legitimately want them on different brokers. The
 * repeated [kafkaBootstrap] is the deliberate cost of that.
 *
 * [kafkaBootstrap] is resolved from the *generator's* vantage point — an in-cluster run names the
 * cluster-internal broker, a local or host run a reachable one. A consumer sending commands
 * resolves its own address to the same broker; it does not reuse this value.
 */
data class ControlSpec(
    @JsonProperty("kafka_bootstrap") val kafkaBootstrap: String,
    val topic: String,
)
