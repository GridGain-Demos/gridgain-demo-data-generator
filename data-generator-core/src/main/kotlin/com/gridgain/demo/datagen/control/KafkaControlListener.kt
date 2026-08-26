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
 * Consumes [ControlCommand]s from a Kafka topic and applies them to this instance: a
 * [SetRateCommand] re-paces the rate limiter, a [StopCommand] ends the run.
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
 *
 * Both actions arrive as callbacks rather than as the objects they act on, so this class stays a
 * transport adapter with no view of the scenario package, and both paths are drivable in a test
 * without a broker.
 */
class KafkaControlListener(
    private val bootstrapServers: String,
    private val topic: String,
    private val runGroup: String,
    private val runId: String,
    private val setRate: (Double) -> Unit,
    private val requestStop: () -> Unit,
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

    /** Parses one payload, drops it unless it addresses this run group, and dispatches on its kind.
     *  Internal so the filter/dispatch behaviour is testable without a broker. */
    internal fun handle(json: String?) {
        if (json.isNullOrBlank()) return
        val node = runCatching { mapper.readTree(json) }.getOrElse {
            log.warn("Ignoring malformed control command: {}", it.message)
            return
        }
        // `kind` is required with no fallback: guessing a kind for an undiscriminated payload would
        // mean guessing whether the operator asked for a rate or a stop.
        val kind = node.get("kind")?.takeIf { it.isTextual }?.asText()
        if (kind == null) {
            log.warn(
                "Ignoring control command with no 'kind' discriminator (accepted kinds: {}): {}",
                ACCEPTED_KINDS, json,
            )
            return
        }
        val required = REQUIRED_FIELDS_BY_KIND[kind]
        if (required == null) {
            log.warn("Ignoring control command of unknown kind '{}' (accepted kinds: {})", kind, ACCEPTED_KINDS)
            return
        }
        // Presence is checked explicitly rather than inferred from a Jackson default: a missing
        // `Double` creator parameter becomes 0.0, and 0.0 means "pause", so a truncated or
        // half-written `set_rate` would otherwise silently stop the whole fleet.
        val missing = required.filterNot { node.hasNonNull(it) }
        if (missing.isNotEmpty()) {
            log.warn("Ignoring '{}' control command missing required field(s) {}: {}", kind, missing, json)
            return
        }
        val command = runCatching { mapper.treeToValue(node, ControlCommand::class.java) }.getOrElse {
            log.warn("Ignoring unreadable control command: {}", it.message)
            return
        }
        if (command.runGroup != runGroup) return
        when (command) {
            is SetRateCommand -> {
                runCatching { setRate(command.targetTpsPerInstance) }.onFailure {
                    log.warn(
                        "Ignoring unusable control command ({}): {}",
                        command.targetTpsPerInstance, it.message,
                    )
                    return
                }
                log.info("Control: target rate for this instance set to {} ops/sec", command.targetTpsPerInstance)
            }
            is StopCommand -> {
                // Honoured whatever the scenario's duration is: this is an operator instruction, and
                // refusing it for a timed run would be surprising.
                runCatching { requestStop() }.onFailure {
                    log.warn("Ignoring unusable stop command: {}", it.message)
                    return
                }
                log.info("Control: stop requested; this instance will finish its in-flight operation and stop.")
            }
        }
    }

    override fun close() {
        stopped = true
        consumer?.wakeup()
    }

    private companion object {
        val REQUIRED_FIELDS_BY_KIND: Map<String, List<String>> = mapOf(
            SetRateCommand.KIND to SetRateCommand.REQUIRED_FIELDS,
            StopCommand.KIND to StopCommand.REQUIRED_FIELDS,
        )
        val ACCEPTED_KINDS: List<String> = REQUIRED_FIELDS_BY_KIND.keys.toList()
    }
}
