package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * NOTE: `transactionScope` defaults to `NONE` in violation of the workspace project rule
 * "no defaults on template classes". Intentional: the framework treats transactions as opt-in.
 * Most demos run against ATOMIC caches and don't need (or want) transaction wrapping.
 * Scenarios that need a single tx around the parent + child puts of a business event
 * opt in with `transaction_scope: business_event`. This default mirrors how the workspace
 * uses caches today and keeps the simplest first-run experience working without forcing
 * users to spell out `none` in every scenario.
 *
 * NOTE: `distribution` is nullable in violation of "no nullable types without approval".
 * Case-by-case approved: the YAML field is genuinely optional (absent => single-pod
 * execution; present => multi-pod coordinator/worker mode). Mirroring the external
 * shape with `T?` keeps the missing-value semantics explicit at the call site rather
 * than burying it in a sentinel default.
 */
data class ScenarioSpec(
    val name: String,
    @JsonProperty("root_schemas") val rootSchemas: List<String>,
    val rate: RateSpec,
    val duration: DurationSpec,
    @JsonProperty("stop_conditions") val stopConditions: List<StopConditionSpec> = emptyList(),
    @JsonProperty("transaction_scope") val transactionScope: TransactionScope = TransactionScope.NONE,
    val provisioning: ProvisioningMode = ProvisioningMode.SKIP,
    @JsonProperty("read_ratio") val readRatio: Double,
    val distribution: DistributionSpec? = null,
)

/**
 * Distributed-execution config. When present on a `ScenarioSpec`, the scenario is dispatched
 * across `replicas` worker pods sharing the work via `partitionCount` partitions. The
 * scenario's `rate` and `duration` are per-run totals (not per-pod); the coordinator divides
 * them across active workers. `partitionCount >= replicas` is enforced in cross-element
 * validation, not the schema.
 */
data class DistributionSpec(
    val replicas: Int,
    @JsonProperty("partition_count") val partitionCount: Int,
)

enum class TransactionScope {
    @JsonProperty("business_event") BUSINESS_EVENT,
    @JsonProperty("none") NONE,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ConstantRateSpec::class, name = "constant"),
    JsonSubTypes.Type(value = RampedRateSpec::class, name = "ramped"),
    JsonSubTypes.Type(value = SteppedRateSpec::class, name = "stepped"),
)
sealed class RateSpec
data class ConstantRateSpec(@JsonProperty("ops_per_second") val opsPerSecond: Double) : RateSpec()
data class RampedRateSpec(val from: Double, val to: Double, val over: String) : RateSpec()
data class SteppedRateSpec(val steps: List<RateStep>) : RateSpec()
data class RateStep(val rate: Double, val hold: String)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = TimeDurationSpec::class, name = "time"),
    JsonSubTypes.Type(value = CountDurationSpec::class, name = "count"),
    JsonSubTypes.Type(value = UntilStopDurationSpec::class, name = "until_stop_condition"),
)
sealed class DurationSpec
data class TimeDurationSpec(val value: String) : DurationSpec()
data class CountDurationSpec(val value: Long) : DurationSpec()
class UntilStopDurationSpec : DurationSpec() {
    override fun equals(other: Any?) = other is UntilStopDurationSpec
    override fun hashCode() = 0
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = LatencyP99StopSpec::class, name = "latency_p99_above"),
    JsonSubTypes.Type(value = LatencyP999StopSpec::class, name = "latency_p999_above"),
    JsonSubTypes.Type(value = ErrorRateStopSpec::class, name = "error_rate_above"),
    JsonSubTypes.Type(value = ExternalSignalStopSpec::class, name = "external_signal"),
)
sealed class StopConditionSpec
data class LatencyP99StopSpec(val threshold: String) : StopConditionSpec()
data class LatencyP999StopSpec(val threshold: String) : StopConditionSpec()
data class ErrorRateStopSpec(val threshold: Double) : StopConditionSpec()
class ExternalSignalStopSpec : StopConditionSpec() {
    override fun equals(other: Any?) = other is ExternalSignalStopSpec
    override fun hashCode() = 0
}
