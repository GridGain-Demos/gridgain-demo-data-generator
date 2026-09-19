package com.gridgain.demo.datagen.config

/**
 * Optional runtime control channel (v5+): a Kafka topic the generator consumes commands from, so an
 * external driver such as the demo UI can raise and lower the load while the run is in flight.
 * Absent (`control:` omitted) means the scenario's configured rate schedule is the only thing
 * pacing the run — which is the behaviour every run had before v5.
 *
 * Kept separate from [MetricsSpec] rather than folded into it: telemetry out and commands in are
 * independent concerns, and a deployment may legitimately want them on different brokers. The
 * repeated [broker] is the deliberate cost of that.
 *
 * [broker] names a `message_brokers` element resolved at launch, or carries a literal address
 * resolved from the *generator's* vantage point. See [BrokerRef]. A consumer sending commands
 * resolves its own address to the same broker; it does not reuse this value.
 */
data class ControlSpec(
    val broker: BrokerRef,
    val topic: String,
)
