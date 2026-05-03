package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.target.Target
import java.time.Duration
import java.time.Instant

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val generator: BusinessEventGenerator,
    private val target: Target,
    /** Hard ceiling for `until_stop_condition` durations to prevent test runaways. */
    private val untilStopCap: Duration = Duration.ofMinutes(1),
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
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val targetDuration = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < targetDuration) {
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> {
                while (Duration.between(started, Instant.now()) < untilStopCap) {
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
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

    private data class OneOutcome(val success: Boolean)

    private fun doOne(rateLimiter: RateLimiter, evaluator: StopConditionEvaluator): OneOutcome {
        rateLimiter.acquire()
        val t0 = System.nanoTime()
        val outcome = target.write(generator.next())
        val latencyNanos = System.nanoTime() - t0
        evaluator.recordOutcome(success = outcome.success, latencyNanos = latencyNanos)
        return OneOutcome(success = outcome.success)
    }

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> RampedRateLimiter(
            fromOpsPerSecond = r.from,
            toOpsPerSecond = r.to,
            rampDuration = Duration.parse(r.over),
        )
        is SteppedRateSpec -> SteppedRateLimiter(
            steps = r.steps.map { StepConfig(rate = it.rate, hold = Duration.parse(it.hold)) },
        )
    }
}
