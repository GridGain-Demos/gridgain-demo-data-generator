package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.config.LatencyP999StopSpec
import com.gridgain.demo.datagen.config.StopConditionSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.time.Duration

class StopConditionEvaluator(private val conditions: List<StopConditionSpec>) {

    init {
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec, is LatencyP99StopSpec, is LatencyP999StopSpec -> Unit  // supported
                is ExternalSignalStopSpec -> throw MisconfigurationException(
                    "Stop condition kind 'external_signal' is designed but not implemented in this build. " +
                    "A future plan will wire external-signal stop conditions."
                )
            }
        }
    }

    private var successCount: Long = 0
    private var failureCount: Long = 0
    private val latency: LatencyHistogram = LatencyHistogram()

    fun recordOutcome(success: Boolean, latencyNanos: Long = 0L) {
        if (success) successCount++ else failureCount++
        if (latencyNanos > 0) latency.record(latencyNanos)
    }

    fun shouldStop(): String? {
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
                is ExternalSignalStopSpec -> Unit  // never reached; rejected at construction
            }
        }
        return null
    }
}
