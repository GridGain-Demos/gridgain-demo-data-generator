package com.gridgain.demo.datagen.observability

import java.nio.file.Path

/**
 * Lifecycle events emitted around scenario, provisioning, and state-persistence boundaries.
 * Sealed for exhaustive `when` over the concrete cases in `RunLog.emit`. Each event carries
 * a stable `name()` (event name in yaml + OTel log body) and a flat `toAttributes()` map
 * (yaml `attributes:` doc field + OTel log attributes).
 *
 * `name()` values are the spec §7-named events: `scenario.started`, `scenario.stopped`,
 * `provisioning.applied`, `state.persisted`.
 */
sealed class LifecycleEvent {
    abstract fun name(): String
    abstract fun toAttributes(): Map<String, String>

    data class ScenarioStarted(val scenarioName: String, val targetName: String) : LifecycleEvent() {
        override fun name() = "scenario.started"
        override fun toAttributes() = linkedMapOf("scenario" to scenarioName, "target" to targetName)
    }
    data class ScenarioStopped(
        val scenarioName: String, val reason: String, val successCount: Long, val errorCount: Long,
    ) : LifecycleEvent() {
        override fun name() = "scenario.stopped"
        override fun toAttributes() = linkedMapOf(
            "scenario" to scenarioName, "reason" to reason,
            "success_count" to successCount.toString(), "error_count" to errorCount.toString(),
        )
    }
    data class ProvisioningApplied(
        val flavor: String, val mode: String,
        val artifactsWritten: Int, val createdCount: Int, val existedCount: Int,
    ) : LifecycleEvent() {
        override fun name() = "provisioning.applied"
        override fun toAttributes() = linkedMapOf(
            "flavor" to flavor, "mode" to mode,
            "artifacts_written" to artifactsWritten.toString(),
            "created_count" to createdCount.toString(),
            "existed_count" to existedCount.toString(),
        )
    }
    data class StatePersisted(
        val stateFile: Path, val sequenceCount: Int, val keyCount: Int, val runHistorySize: Int,
    ) : LifecycleEvent() {
        override fun name() = "state.persisted"
        override fun toAttributes() = linkedMapOf(
            "state_file" to stateFile.toString(),
            "sequence_count" to sequenceCount.toString(),
            "key_count" to keyCount.toString(),
            "run_history_size" to runHistorySize.toString(),
        )
    }
}
