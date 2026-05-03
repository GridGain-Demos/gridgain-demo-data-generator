package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.config.LatencyP999StopSpec
import com.gridgain.demo.datagen.config.StopConditionSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException

class StopConditionEvaluator(private val conditions: List<StopConditionSpec>) {

    init {
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec -> Unit
                is LatencyP99StopSpec, is LatencyP999StopSpec -> throw MisconfigurationException(
                    "Stop condition kind '${if (c is LatencyP99StopSpec) "latency_p99_above" else "latency_p999_above"}' " +
                    "is designed but not implemented in this build. " +
                    "Plan 5 will wire latency-based stop conditions when real KV targets land. " +
                    "Use error_rate_above for now."
                )
                is ExternalSignalStopSpec -> throw MisconfigurationException(
                    "Stop condition kind 'external_signal' is designed but not implemented in this build. " +
                    "Plan 5 or later will wire external-signal stop conditions."
                )
            }
        }
    }

    private var successCount: Long = 0
    private var failureCount: Long = 0

    fun recordOutcome(success: Boolean) {
        if (success) successCount++ else failureCount++
    }

    /** Returns a stop reason string if any condition has triggered, else null. Requires at least 100 samples. */
    fun shouldStop(): String? {
        val total = successCount + failureCount
        if (total < 100) return null
        val errorRate = failureCount.toDouble() / total
        for (c in conditions) {
            if (c is ErrorRateStopSpec && errorRate > c.threshold) {
                return "error_rate exceeded threshold: $errorRate > ${c.threshold}"
            }
        }
        return null
    }
}
