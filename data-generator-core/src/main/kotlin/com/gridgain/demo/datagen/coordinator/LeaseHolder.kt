package com.gridgain.demo.datagen.coordinator

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Thin wrapper around Fabric8's [io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector]
 * configured against a `coordination.k8s.io/v1` `Lease`. Each pod constructs one [LeaseHolder]
 * at startup; the holder calls back on transitions:
 *
 * - [onBecameLeader] when this pod wins the election (and on any subsequent re-acquire).
 * - [onLostLeadership] when this pod was the leader and lost the lease.
 * - [onNewLeader] when some other pod (or this one) is observed as the current leader.
 *
 * Callbacks fire on Fabric8's executor thread; consumers MUST make their handlers
 * thread-safe and quick (the elector loop is shared with renewal).
 *
 * Defaults: 15 s lease duration, 10 s renew deadline, 2 s retry period — the standard
 * k8s controller cadence as documented in the C1 design note.
 */
class LeaseHolder(
    private val client: KubernetesClient,
    private val namespace: String,
    private val leaseName: String,
    private val identity: String,
    private val leaseDuration: Duration = Duration.ofSeconds(15),
    private val renewDeadline: Duration = Duration.ofSeconds(10),
    private val retryPeriod: Duration = Duration.ofSeconds(2),
    private val onBecameLeader: () -> Unit,
    private val onLostLeadership: () -> Unit,
    private val onNewLeader: (String) -> Unit = {},
) {

    @Volatile private var future: CompletableFuture<*>? = null

    fun start() {
        check(future == null) { "LeaseHolder.start() called twice for lease '$leaseName'." }
        val callbacks = LeaderCallbacks(
            /* onStartLeading = */ { onBecameLeader() },
            /* onStopLeading  = */ { onLostLeadership() },
            /* onNewLeader    = */ { id -> onNewLeader(id) },
        )
        val config = LeaderElectionConfigBuilder()
            .withName(leaseName)
            .withLeaseDuration(leaseDuration)
            .withRenewDeadline(renewDeadline)
            .withRetryPeriod(retryPeriod)
            .withLock(LeaseLock(namespace, leaseName, identity))
            .withLeaderCallbacks(callbacks)
            .build()
        future = client.leaderElector().withConfig(config).build().start()
    }

    /**
     * Cancels the leader-election loop. If this pod was the holder, the lease record stays
     * in etcd until its current term expires (`leaseDurationSeconds`) so the next election
     * cycle can proceed cleanly without a forced takeover.
     */
    fun stop() {
        future?.cancel(true)
        future = null
    }
}
