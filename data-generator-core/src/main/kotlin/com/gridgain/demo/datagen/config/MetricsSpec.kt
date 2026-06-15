package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Optional live-metrics export (throughput + per-op execution latency) to a Kafka topic, so an
 * external consumer can render real-time graphs without scraping/monitoring infrastructure.
 * Absent (`metrics:` omitted) means no live export — the generator still records OTel instruments.
 *
 * [kafkaBootstrap] is resolved from the generator's vantage point: an in-cluster run targets the
 * cluster-internal broker DNS, a local run a reachable bootstrap. [intervalMs] is how often a
 * snapshot is published (defaults to 1s; the JSONSchema records the default).
 */
data class MetricsSpec(
    @JsonProperty("kafka_bootstrap") val kafkaBootstrap: String,
    val topic: String,
    @JsonProperty("interval_ms") val intervalMs: Long = 1_000L,
)
