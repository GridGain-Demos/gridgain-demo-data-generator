package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.config.LatencyP999StopSpec
import com.gridgain.demo.datagen.config.StopConditionSpec
import java.time.Duration

/**
 * Evaluates a scenario's `stop_conditions` after each operation.
 *
 * [stopSignal] is required, not defaulted, because an `external_signal` condition evaluated against
 * a signal nobody raises is a run that can never stop — exactly the trap the condition exists to
 * avoid. Handing one in is how the operator's `stop` command (and a SIGTERM) reaches the condition.
 */
class StopConditionEvaluator(
    private val conditions: List<StopConditionSpec>,
    private val stopSignal: StopSignal,
) {

    /** True when the scenario declared `external_signal`, so the signal is one of its stop
     *  conditions rather than only the run loop's catch-all. */
    private val watchesExternalSignal: Boolean = conditions.any { it is ExternalSignalStopSpec }

    private var successCount: Long = 0
    private var failureCount: Long = 0
    private val latency: LatencyHistogram = LatencyHistogram()

    fun recordOutcome(success: Boolean, latencyNanos: Long = 0L) {
        if (success) successCount++ else failureCount++
        if (latencyNanos > 0) latency.record(latencyNanos)
    }

    fun shouldStop(): String? {
        // Checked ahead of the sample-size gate below, deliberately. The gate exists so a
        // rate/latency threshold is not judged on a handful of samples; an operator pressing stop is
        // not a statistic, and at a low rate the 100th operation could be minutes away — far past
        // any shutdown grace period.
        if (watchesExternalSignal) {
            val raised = stopSignal.reason()
            if (raised != null) return "external_signal raised: $raised"
        }
        val total = successCount + failureCount
        if (total < 100) return null
        val errorRate = failureCount.toDouble() / total
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec -> {
                    if (errorRate > c.threshold) {
                        return "error_rate exceeded threshold: $errorRate > ${c.threshold}"
                    }
                }
                is LatencyP99StopSpec -> {
                    val q = latency.quantile(0.99) ?: continue
                    val thresholdNanos = Duration.parse(c.threshold).toNanos()
                    if (q > thresholdNanos) {
                        return "latency_p99 exceeded threshold: ${q}ns > ${thresholdNanos}ns"
                    }
                }
                is LatencyP999StopSpec -> {
                    val q = latency.quantile(0.999) ?: continue
                    val thresholdNanos = Duration.parse(c.threshold).toNanos()
                    if (q > thresholdNanos) {
                        return "latency_p999 exceeded threshold: ${q}ns > ${thresholdNanos}ns"
                    }
                }
                is ExternalSignalStopSpec -> Unit  // handled above, ahead of the sample-size gate
            }
        }
        return null
    }
}
