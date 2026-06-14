package com.gridgain.demo.datagen.metrics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Duration
import java.util.Properties
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

/**
 * A [MetricsSink] that publishes each snapshot as JSON to a Kafka topic, keyed by run id. Used by
 * the demo so the generator's throughput/latency reaches the UI backend over the shared Kafka bus
 * regardless of where the generator runs (in-cluster Job or local fork) — no shared filesystem.
 *
 * Fire-and-forget (`acks=0`, bounded `max.block.ms`): metrics are a live gauge, so a dropped point
 * during a broker hiccup self-heals on the next interval and must never stall the generator.
 */
class KafkaMetricsSink(
    bootstrapServers: String,
    private val topic: String,
) : MetricsSink, AutoCloseable {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val producer = KafkaProducer<String, String>(Properties().apply {
        put("bootstrap.servers", bootstrapServers)
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("client.id", "datagen-live-metrics")
        put("acks", "0")
        put("linger.ms", "0")
        put("max.block.ms", "2000")
    })

    override fun emit(snapshot: MetricsSnapshot) {
        producer.send(ProducerRecord(topic, snapshot.runId, mapper.writeValueAsString(snapshot)))
    }

    override fun close() {
        runCatching { producer.flush() }
        producer.close(Duration.ofSeconds(2))
    }
}
