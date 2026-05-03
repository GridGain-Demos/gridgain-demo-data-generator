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
import com.gridgain.demo.datagen.target.Target
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
) {
    private val keyRegistry = KeyRegistry()
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

        val t0 = System.nanoTime()
        val success = if (isRead) {
            val key = keyRegistry.sample(rootSchemaName, decisionRandom)!!
            target.read(rootSchemaName, key).success
        } else {
            val event = generator.next()
            val rootSchema = schemasByName[rootSchemaName]!!
            val finalEvent = maybeApplyUpdate(event, rootSchema, rootKeyColumn)
            // Register the (possibly substituted) parent key for future updates/reads.
            val parentKey = finalEvent.parentRow[rootKeyColumn]!!
            keyRegistry.register(rootSchemaName, parentKey)
            // Register children's keys too.
            finalEvent.childrenBySchema.forEach { (childSchema, rows) ->
                val childKeyColumn = keyColumnByName[childSchema] ?: return@forEach
                rows.forEach { row -> row[childKeyColumn]?.let { keyRegistry.register(childSchema, it) } }
            }
            target.write(finalEvent).success
        }
        val latencyNanos = System.nanoTime() - t0
        evaluator.recordOutcome(success = success, latencyNanos = latencyNanos)
        return success
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
}
