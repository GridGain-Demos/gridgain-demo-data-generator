package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.metrics.MetricsRecorder
import com.gridgain.demo.datagen.observability.Instruments
import com.gridgain.demo.datagen.state.KeyRegistryState
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.TransactionOutcome
import java.time.Duration
import java.time.Instant
import java.util.Random

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val data: DataConfig,
    private val generator: BusinessEventGenerator,
    private val target: Target,
    private val untilStopCap: Duration = Duration.ofMinutes(1),
    private val decisionRandom: Random = Random(),
    private val keyRegistry: KeyRegistry = KeyRegistry(),
    private val instruments: Instruments = Instruments.noop(),
    private val targetName: String = "<unknown>",
    // Live throughput/latency counters. Defaults to a detached recorder no consumer reads, so
    // the metric collection is opt-in by wiring a LiveMetricsWriter to the same instance.
    private val metrics: MetricsRecorder = MetricsRecorder(),
) {

    private var totalAttempts: Long = 0L
    private var startedNanos: Long = 0L
    /** Captures the post-run registry contents for `state.yaml`. Safe to call multiple times. */
    fun keyRegistrySnapshot(): List<KeyRegistryState> = keyRegistry.snapshot()

    private val schemasByName: Map<String, SchemaSpec> = data.schemas.associateBy { it.name }
    private val keyColumnByName: Map<String, String> = data.schemas.associate { schema ->
        schema.name to (schema.columns.firstOrNull { it.key }?.name
            ?: throw MisconfigurationException(
                "schema '${schema.name}' has no column marked 'key: true'. " +
                "ScenarioRunner requires the KeyColumnValidator to have passed before construction."
            ))
    }

    fun run(): ScenarioResult {
        val rateLimiter = buildRateLimiter()
        val evaluator = StopConditionEvaluator(scenario.stopConditions)
        instruments.targetRateRef.set(configuredStartRate(scenario))
        totalAttempts = 0L
        startedNanos = System.nanoTime()
        val started = Instant.now()
        var success = 0L
        var error = 0L
        var stopReason = ""

        when (val d = scenario.duration) {
            is CountDurationSpec -> {
                while (success + error < d.value) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val td = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < td) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> {
                while (Duration.between(started, Instant.now()) < untilStopCap) {
                    val s = tick(rateLimiter, evaluator)
                    if (s) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "until_stop_condition cap reached"
            }
        }

        val wall = Duration.between(started, Instant.now())
        val achievedRate = if (wall.toNanos() > 0)
            (success + error).toDouble() / (wall.toNanos() / 1_000_000_000.0) else 0.0
        return ScenarioResult(
            scenarioName = scenario.name,
            achievedRate = achievedRate,
            errorCount = error,
            successCount = success,
            stopReason = stopReason,
            wallTime = wall,
        )
    }

    private fun tick(rateLimiter: RateLimiter, evaluator: StopConditionEvaluator): Boolean {
        rateLimiter.acquire()
        val rootSchemaName = scenario.rootSchemas.first()  // multi-root weighting deferred
        val rootKeyColumn = keyColumnByName[rootSchemaName]!!
        val isRead = scenario.readRatio > 0.0 &&
            target.supportsReads &&
            decisionRandom.nextDouble() < scenario.readRatio &&
            keyRegistry.size(rootSchemaName) > 0
        val op = if (isRead) "get" else "put"
        val attrs = instruments.opAttributes(scenario.name, targetName, rootSchemaName, op)

        instruments.inFlight.add(1, attrs)
        val t0 = System.nanoTime()
        var transactionOutcome: TransactionOutcome = TransactionOutcome.NONE
        val success: Boolean = try {
            if (isRead) {
                val key = keyRegistry.sample(rootSchemaName, decisionRandom)!!
                target.read(rootSchemaName, key).success
            } else {
                val event = generator.next()
                val rootSchema = schemasByName[rootSchemaName]!!
                val finalEvent = maybeApplyUpdate(event, rootSchema, rootKeyColumn)
                val parentKey = finalEvent.parentRow[rootKeyColumn]!!
                keyRegistry.register(rootSchemaName, parentKey)
                finalEvent.childrenBySchema.forEach { (childSchema, rows) ->
                    val childKeyColumn = keyColumnByName[childSchema] ?: return@forEach
                    rows.forEach { row -> row[childKeyColumn]?.let { keyRegistry.register(childSchema, it) } }
                }
                val outcome = target.write(finalEvent)
                transactionOutcome = outcome.transactionOutcome
                outcome.success
            }
        } catch (e: Exception) {
            instruments.opErrors.add(1, attrs.toBuilder()
                .put(Instruments.ATTR_EXCEPTION, e.javaClass.simpleName).build())
            false
        } finally {
            instruments.inFlight.add(-1, attrs)
        }
        val latencyNanos = System.nanoTime() - t0
        // Same latency the OTel histogram gets — the wall time of the target operation (the
        // GridGain write/read), which is the "execution latency" the live graph reports.
        metrics.record(latencyNanos = latencyNanos, success = success)
        instruments.opLatency.record(latencyNanos.toDouble(), attrs)
        instruments.opCount.add(1, attrs)
        if (!success) instruments.opErrors.add(1, attrs.toBuilder()
            .put(Instruments.ATTR_EXCEPTION, "TargetReportedFailure").build())

        // F12: emit tx_commit / tx_rollback as separate op-tagged points alongside the
        // underlying put. Latency for the tx op is the wall time of the wrapped event —
        // a meaningful proxy for "how long committed/rolled-back transactions take" in
        // aggregate. The error counter for a rollback is already recorded above on op=put,
        // so we don't double-count exceptions here.
        when (transactionOutcome) {
            TransactionOutcome.COMMITTED -> emitTxOp(rootSchemaName, "tx_commit", latencyNanos)
            TransactionOutcome.ROLLED_BACK -> emitTxOp(rootSchemaName, "tx_rollback", latencyNanos)
            TransactionOutcome.NONE -> { /* no transaction wrap; nothing to emit */ }
        }
        evaluator.recordOutcome(success = success, latencyNanos = latencyNanos)

        totalAttempts++
        val elapsedSec = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        if (elapsedSec > 0) instruments.observedRateRef.set(totalAttempts / elapsedSec)
        return success
    }

    private fun emitTxOp(schemaName: String, op: String, latencyNanos: Long) {
        val txAttrs = instruments.opAttributes(scenario.name, targetName, schemaName, op)
        instruments.opLatency.record(latencyNanos.toDouble(), txAttrs)
        instruments.opCount.add(1, txAttrs)
    }

    private fun maybeApplyUpdate(event: BusinessEvent, rootSchema: SchemaSpec, keyColumn: String): BusinessEvent {
        if (rootSchema.updateRatio > 0.0 &&
            keyRegistry.size(rootSchema.name) > 0 &&
            decisionRandom.nextDouble() < rootSchema.updateRatio
        ) {
            val existingKey = keyRegistry.sample(rootSchema.name, decisionRandom)!!
            val newParent = LinkedHashMap(event.parentRow)
            newParent[keyColumn] = existingKey
            return event.copy(parentRow = newParent)
        }
        return event
    }

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> RampedRateLimiter(
            fromOpsPerSecond = r.from, toOpsPerSecond = r.to,
            rampDuration = Duration.parse(r.over),
        )
        is SteppedRateSpec -> SteppedRateLimiter(
            steps = r.steps.map { StepConfig(rate = it.rate, hold = Duration.parse(it.hold)) },
        )
    }

    companion object {
        /** The scenario's initial target rate (ops/sec): the constant rate, a ramp's start
         *  value, or the first step. Seeds both the OTel target-rate gauge and the live-metrics
         *  `targetTps` so a graph can show requested-vs-achieved. */
        fun configuredStartRate(scenario: ScenarioSpec): Double = when (val r = scenario.rate) {
            is ConstantRateSpec -> r.opsPerSecond
            is RampedRateSpec -> r.from
            is SteppedRateSpec -> r.steps.first().rate
        }
    }
}
