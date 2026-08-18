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
 *
 * [histogramHighestMs] and [histogramSignificantDigits] are the bounds of the whole-run latency
 * histogram carried on every snapshot — see
 * [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds]. Both are required from schema v6
 * onward; there is no Kotlin default, per the comprehensive-configuration-file policy. A v5
 * document being migrated forward has [MigrateOpsV5toV6] fill them into its existing `metrics:`
 * block, so an existing ops.yaml upgrades without hand-editing.
 */
data class MetricsSpec(
    @JsonProperty("kafka_bootstrap") val kafkaBootstrap: String,
    val topic: String,
    /** See [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds.highestMs]. */
    @JsonProperty("histogram_highest_ms") val histogramHighestMs: Long,
    /** See [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds.significantDigits]. */
    @JsonProperty("histogram_significant_digits") val histogramSignificantDigits: Int,
    @JsonProperty("interval_ms") val intervalMs: Long = 1_000L,
)
