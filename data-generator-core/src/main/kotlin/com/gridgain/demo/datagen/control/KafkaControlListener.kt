package com.gridgain.demo.datagen.control

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Duration
import java.util.Properties
import java.util.UUID
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

/**
 * Consumes [ControlCommand]s from a Kafka topic and applies them to this instance's rate limiter.
 *
 * Uses the same bus the live-metrics feed already travels, which is what makes runtime load control
 * work identically for an in-cluster pod, a local fork, and a host/VM install — no listening port,
 * Service, ingress or port-forward is involved anywhere.
 *
 * **Every instance gets a unique consumer group** (`datagen-control-<runId>`), so the topic
 * broadcasts: a single command from the UI reaches every instance of the fleet rather than being
 * load-balanced to one of them. Combined with `auto.offset.reset=latest`, a starting instance picks
 * up the next command rather than replaying the run's history.
 *
 * Failures are contained: a malformed payload or a rejected rate is logged and skipped, and a
 * broker outage retries with backoff. Losing control must never take down a running load test.
 */
class KafkaControlListener(
    private val bootstrapServers: String,
    private val topic: String,
    private val runGroup: String,
    private val runId: String,
    private val setRate: (Double) -> Unit,
) : ControlListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().registerKotlinModule()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val thread = Thread(::run, "datagen-control").apply { isDaemon = true }

    @Volatile private var consumer: KafkaConsumer<String, String>? = null
    @Volatile private var stopped = false

    override fun start() {
        thread.start()
    }

    private fun run() {
        while (!stopped) {
            try {
                pollLoop()
            } catch (_: WakeupException) {
                return
            } catch (e: Exception) {
                log.warn("Control listener error (retrying in 5s): {}", e.message)
                runCatching { Thread.sleep(5_000) }
            }
        }
    }

    private fun pollLoop() {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            // Unique per instance => broadcast, not load balancing. Every instance must see
            // every command, otherwise only one pod of a fleet would change rate.
            put("group.id", "datagen-control-$runId-${UUID.randomUUID()}")
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("auto.offset.reset", "latest")
            put("enable.auto.commit", "true")
            put("reconnect.backoff.ms", "1000")
            put("reconnect.backoff.max.ms", "10000")
        }
        val c = KafkaConsumer<String, String>(props)
        consumer = c
        c.use { kc ->
            kc.subscribe(listOf(topic))
            while (!stopped) {
                for (rec in kc.poll(Duration.ofMillis(500))) {
                    handle(rec.value())
                }
            }
        }
    }

    /** Parses one payload, drops it unless it addresses this run group, and applies the new rate.
     *  Internal so the filter/apply behaviour is testable without a broker. */
    internal fun handle(json: String?) {
        if (json.isNullOrBlank()) return
        val node = runCatching { mapper.readTree(json) }.getOrElse {
            log.warn("Ignoring malformed control command: {}", it.message)
            return
        }
        // Jackson fills a missing `Double` creator parameter with 0.0 rather than failing, and 0.0
        // means "pause". A truncated or half-written payload would therefore silently stop the
        // whole fleet, so presence is checked explicitly instead of being inferred from a default.
        val missing = REQUIRED_FIELDS.filterNot { node.hasNonNull(it) }
        if (missing.isNotEmpty()) {
            log.warn("Ignoring control command missing required field(s) {}: {}", missing, json)
            return
        }
        val command = runCatching { mapper.treeToValue(node, ControlCommand::class.java) }.getOrElse {
            log.warn("Ignoring unreadable control command: {}", it.message)
            return
        }
        if (command.runGroup != runGroup) return
        runCatching { setRate(command.targetTpsPerInstance) }.onFailure {
            log.warn("Ignoring unusable control command ({}): {}", command.targetTpsPerInstance, it.message)
            return
        }
        log.info("Control: target rate for this instance set to {} ops/sec", command.targetTpsPerInstance)
    }

    override fun close() {
        stopped = true
        consumer?.wakeup()
    }

    private companion object {
        val REQUIRED_FIELDS = listOf("runGroup", "targetTpsPerInstance", "issuedAtMs")
    }
}
