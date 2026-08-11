package com.gridgain.demo.datagen.control

/**
 * An instruction to a running generator fleet, delivered over the control channel.
 *
 * [runGroup] addresses the command: every instance the toolkit launched together shares one group
 * id, and an instance ignores anything not addressed to its own. That is what lets a single control
 * topic serve several concurrent runs.
 *
 * [targetTpsPerInstance] is **per instance, not the fleet total**. An instance cannot reliably know
 * how many peers are live, so the sender — which does — performs the division. `0.0` pauses the
 * instance; it stays connected and keeps reporting metrics, so the graphs flatline rather than
 * disappearing.
 *
 * Serialized as camelCase JSON, matching the live-metrics feed that travels the other way.
 */
data class ControlCommand(
    val runGroup: String,
    val targetTpsPerInstance: Double,
    val issuedAtMs: Long,
)
