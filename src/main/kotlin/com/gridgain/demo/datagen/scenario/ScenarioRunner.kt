package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.target.Target
import java.time.Duration
import java.time.Instant

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val generator: BusinessEventGenerator,
    private val target: Target,
) {
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
                    rateLimiter.acquire()
                    val outcome = target.write(generator.next())
                    if (outcome.success) success++ else error++
                    evaluator.recordOutcome(outcome.success)
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val targetDuration = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < targetDuration) {
                    rateLimiter.acquire()
                    val outcome = this.target.write(generator.next())
                    if (outcome.success) success++ else error++
                    evaluator.recordOutcome(outcome.success)
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> throw MisconfigurationException(
                "Duration kind 'until_stop_condition' is designed but not implemented in this build. " +
                "Plan 5 will wire latency-based stop conditions that this depends on. " +
                "Use 'time' or 'count' for now."
            )
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

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> throw MisconfigurationException(
            "Rate kind 'ramped' is designed but not implemented in this build. " +
            "Plan 5 will wire ramped rate. Use 'constant' for now."
        )
        is SteppedRateSpec -> throw MisconfigurationException(
            "Rate kind 'stepped' is designed but not implemented in this build. " +
            "Plan 5 will wire stepped rate. Use 'constant' for now."
        )
    }
}
