package com.gridgain.demo.datagen.control

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * An instruction to a running generator fleet, delivered over the control channel.
 *
 * [runGroup] addresses the command: every instance the toolkit launched together shares one group
 * id, and an instance ignores anything not addressed to its own. That is what lets a single control
 * topic serve several concurrent runs.
 *
 * Serialized as camelCase JSON, matching the live-metrics feed that travels the other way, with a
 * `kind` discriminator — the same shape every other union in this codebase uses (`RateSpec`,
 * `DurationSpec`, `StopConditionSpec`, `TargetSpec`). A flat record carrying both a rate and a stop
 * flag would have states that mean nothing, so the two instructions are separate types instead.
 *
 * **`kind` is required and there is no compatibility with the older flat message.** A payload
 * without it is rejected, so every deployed archive and image has to be redeployed alongside any
 * sender that starts emitting the new shape.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = SetRateCommand::class, name = SetRateCommand.KIND),
    JsonSubTypes.Type(value = StopCommand::class, name = StopCommand.KIND),
)
sealed class ControlCommand {
    abstract val runGroup: String

    /** When the sender issued the command. Carried so a consumer can age out a stale instruction. */
    abstract val issuedAtMs: Long
}

/**
 * Re-pace the fleet.
 *
 * [targetTpsPerInstance] is **per instance, not the fleet total**. An instance cannot reliably know
 * how many peers are live, so the sender — which does — performs the division. `0.0` pauses the
 * instance; it stays connected and keeps reporting metrics, so the graphs flatline rather than
 * disappearing.
 */
data class SetRateCommand(
    override val runGroup: String,
    val targetTpsPerInstance: Double,
    override val issuedAtMs: Long,
) : ControlCommand() {
    companion object {
        const val KIND = "set_rate"

        /**
         * Checked for presence before deserialization. Jackson fills a missing `Double` creator
         * parameter with `0.0` rather than failing, and `0.0` means "pause" — so a truncated or
         * half-written payload would silently stop the whole fleet if presence were inferred from a
         * default. See [KafkaControlListener.handle].
         */
        val REQUIRED_FIELDS = listOf("runGroup", "targetTpsPerInstance", "issuedAtMs")
    }
}

/**
 * End the run, now, cleanly. Raises the same stop signal a SIGTERM does, so the instance finishes
 * its in-flight operation, emits its final `active=false` metrics snapshot with whole-run figures
 * and its merged histogram, closes its target and reports a real result.
 *
 * Honoured by **any** run, not only one declaring the `external_signal` stop condition: this is an
 * operator instruction, and refusing it for a timed run would be surprising. What `external_signal`
 * adds is the declaration that a `duration: {kind: until_stop_condition}` scenario is *intentionally*
 * unbounded and ends here.
 */
data class StopCommand(
    override val runGroup: String,
    override val issuedAtMs: Long,
) : ControlCommand() {
    companion object {
        const val KIND = "stop"

        val REQUIRED_FIELDS = listOf("runGroup", "issuedAtMs")
    }
}
