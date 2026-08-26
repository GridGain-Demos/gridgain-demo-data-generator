package com.gridgain.demo.datagen.scenario

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The one stop signal for a run. Several independent sources can ask a run to end, and they all
 * raise this rather than each growing a flag of its own.
 *
 * Today's sources are the JVM shutdown hook (SIGTERM — a teardown task, a deleted pod, a Ctrl-C)
 * and the runtime control channel's `stop` command. Both mean exactly the same thing to the run
 * loop, and both need the *reason* to survive into the `ScenarioResult` and the run log, so one
 * object carrying a reason beats a bare `AtomicBoolean` plus a side channel for the "why".
 *
 * **First raise wins.** A stop signal is a latch, not a level: once a run is stopping it does not
 * un-stop, and the first reason is the true cause. That is what makes a SIGTERM arriving right
 * behind an operator `stop` command report the command rather than the signal it triggered.
 *
 * Thread-safe by construction — raised from a shutdown hook or the control-listener thread, polled
 * from the run loop.
 *
 * [reason] is `String?`, null meaning "not raised", matching the `String?` that
 * [StopConditionEvaluator.shouldStop] already returns and feeds into. A separate boolean plus a
 * value could be read torn; one atomic reference cannot.
 */
class StopSignal {

    private val raisedReason = AtomicReference<String?>(null)

    /** Actions to run the instant the signal is raised — see [onRaise]. */
    private val wakeUps = CopyOnWriteArrayList<() -> Unit>()

    /** True once any source has raised the signal. */
    val isRaised: Boolean get() = raisedReason.get() != null

    /** Why the run is stopping, or null while nothing has asked it to. */
    fun reason(): String? = raisedReason.get()

    /**
     * Raise the signal, recording [reason] as the cause. Returns true when this call is the one
     * that raised it; a later caller gets false and does not overwrite the reason.
     */
    fun raise(reason: String): Boolean {
        require(reason.isNotBlank()) {
            "a stop signal must carry a non-blank reason: it is reported as the run's stop_reason " +
                "and is the only record of why the run ended early."
        }
        if (!raisedReason.compareAndSet(null, reason)) return false
        // Swallowed deliberately: a wake-up exists to unblock something, so one that throws must
        // neither skip the remaining wake-ups nor take down the raising thread — which is a
        // shutdown hook or the control listener, and in both cases has nowhere to report to.
        wakeUps.forEach { runCatching { it() } }
        return true
    }

    /**
     * Register an action to run when the signal is raised, for anything that can be *parked* rather
     * than polling. A paused [ControllableRateLimiter] is the case that forces this to exist: at
     * rate `0.0` the run loop waits on a condition inside `acquire()` and would never reach its own
     * stop check, so the release has to be pushed to it.
     *
     * If the signal is already raised, [action] runs immediately on the caller's thread — a
     * registration must never silently miss a signal that beat it.
     */
    fun onRaise(action: () -> Unit) {
        wakeUps += action
        if (isRaised) runCatching { action() }
    }
}
