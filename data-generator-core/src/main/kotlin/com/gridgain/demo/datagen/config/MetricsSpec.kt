package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Optional live-metrics export (throughput + per-op execution latency) to a Kafka topic, so an
 * external consumer can render real-time graphs without scraping/monitoring infrastructure.
 * Absent (`metrics:` omitted) means no live export — the generator still records OTel instruments.
 *
 * [broker] says which broker to publish to — either a `message_brokers` element by name, resolved
 * at launch from the toolkit's `broker-endpoints.yaml`, or a literal address. See [BrokerRef];
 * from schema v9 it replaces the `kafka_bootstrap` this block used to carry, so the address is no
 * longer duplicated here and cannot go stale when the broker is redeployed. [intervalMs] is how
 * often a snapshot is published (defaults to 1s; the JSONSchema records the default).
 *
 * [histogramHighestMs] and [histogramSignificantDigits] are the bounds of the whole-run latency
 * histogram carried on every snapshot — see
 * [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds]. Both are required from schema v6
 * onward; there is no Kotlin default, per the comprehensive-configuration-file policy. A v5
 * document being migrated forward has [MigrateOpsV5toV6] fill them into its existing `metrics:`
 * block, so an existing ops.yaml upgrades without hand-editing.
 */
data class MetricsSpec(
    val broker: BrokerRef,
    val topic: String,
    /** See [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds.highestMs]. */
    @JsonProperty("histogram_highest_ms") val histogramHighestMs: Long,
    /** See [com.gridgain.demo.datagen.metrics.LatencyHistogramBounds.significantDigits]. */
    @JsonProperty("histogram_significant_digits") val histogramSignificantDigits: Int,
    @JsonProperty("interval_ms") val intervalMs: Long = 1_000L,
)
