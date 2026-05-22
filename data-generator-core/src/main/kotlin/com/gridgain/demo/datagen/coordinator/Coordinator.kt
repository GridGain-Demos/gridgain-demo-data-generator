package com.gridgain.demo.datagen.coordinator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Distributed-mode coordinator. Each pod constructs one [Coordinator] at startup; the
 * elected leader (via [LeaseHolder]) owns partition assignment, while every pod publishes
 * a heartbeat status ConfigMap and reads back its slice from the assignment ConfigMap.
 *
 * Naming convention in the data-gen namespace:
 *   - Lease:           `dg-<scenario>-leader`
 *   - Assignment CM:   `dg-<scenario>-assignment`         (leader writes, all read)
 *   - Status CMs:      `dg-<scenario>-status-<instance>`  (each pod writes its own)
 *
 * Labels on all three resources: `app=data-generator`, `scenario=<scenarioName>`. The
 * leader's worker-set scan filters on these labels.
 *
 * Threading model: callbacks from Fabric8's leader-election thread mutate `leader`. A
 * single-threaded [ScheduledExecutorService] runs the periodic publish + refresh tasks.
 * Both [assignedPartitions] and [leader] are exposed as `@Volatile` snapshots; consumers
 * (the scenario tick loop) read them lock-free.
 *
 * Intentional first-cut omissions (call out in the design note):
 *   - Status CM deletion on shutdown is best-effort; stale CMs from crashed pods will age
 *     out only via the `lastSeen` timestamp check on next coordinator scan.
 *   - The leader does not perform a final merged state.yaml write before releasing the
 *     lease. Acceptable for the load-test mission; documented as future work.
 */
class Coordinator(
    private val client: KubernetesClient,
    private val namespace: String,
    private val scenarioName: String,
    private val instanceId: String,
    private val partitionCount: Int,
    private val statusCadence: Duration = Duration.ofSeconds(5),
    private val staleAfter: Duration = Duration.ofSeconds(15),
    private val leaseDuration: Duration = Duration.ofSeconds(15),
    private val renewDeadline: Duration = Duration.ofSeconds(10),
    private val retryPeriod: Duration = Duration.ofSeconds(2),
    // Test seam: tests inject a deterministic executor.
    private val executorFactory: () -> ScheduledExecutorService =
        { Executors.newSingleThreadScheduledExecutor(NAMED_THREAD_FACTORY) },
) {

    private val executor: ScheduledExecutorService = executorFactory()
    private val started = AtomicBoolean(false)

    private val leaderFlag = AtomicBoolean(false)
    private val assignedRef = AtomicReference<Set<Int>>(emptySet())
    /** Observed by Coordinator-only OTel instruments (rebalance counter, worker-count gauge). */
    val rebalances = ConcurrentHashMap<String, Long>()

    private lateinit var lease: LeaseHolder
    @Volatile private var publishFuture: ScheduledFuture<*>? = null
    @Volatile private var refreshFuture: ScheduledFuture<*>? = null

    /** Set of partition ids this pod currently owns. Empty until first assignment lands. */
    fun assignedPartitions(): Set<Int> = assignedRef.get()
    fun isLeader(): Boolean = leaderFlag.get()

    fun start() {
        check(started.compareAndSet(false, true)) { "Coordinator.start() called twice." }
        lease = LeaseHolder(
            client = client,
            namespace = namespace,
            leaseName = leaseName(scenarioName),
            identity = instanceId,
            leaseDuration = leaseDuration,
            renewDeadline = renewDeadline,
            retryPeriod = retryPeriod,
            onBecameLeader = { leaderFlag.set(true) },
            onLostLeadership = { leaderFlag.set(false) },
        )
        lease.start()
        val cadenceMs = statusCadence.toMillis()
        publishFuture = executor.scheduleAtFixedRate(::publishOwnStatus, 0, cadenceMs, TimeUnit.MILLISECONDS)
        refreshFuture = executor.scheduleAtFixedRate(::refreshAssignment, cadenceMs, cadenceMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!started.get()) return
        publishFuture?.cancel(true)
        refreshFuture?.cancel(true)
        executor.shutdownNow()
        runCatching { lease.stop() }
        // Best-effort: clean up our status CM so the leader's next scan reflects the
        // departure immediately rather than waiting for the staleness threshold.
        runCatching {
            client.configMaps().inNamespace(namespace).withName(statusName(scenarioName, instanceId))
                .delete()
        }
    }

    internal fun publishOwnStatus() {
        val cm = buildConfigMap(
            name = statusName(scenarioName, instanceId),
            role = "status",
            data = mapOf(
                KEY_INSTANCE_ID to instanceId,
                KEY_LAST_SEEN to Instant.now().toString(),
            ),
        )
        upsert(cm)
    }

    internal fun refreshAssignment() {
        if (leaderFlag.get()) {
            recomputeAndWriteAssignment()
        } else {
            readAssignmentFromCm()
        }
    }

    private fun recomputeAndWriteAssignment() {
        val workers = liveWorkers()
        if (workers.isEmpty()) return    // pre-publish race: our own status not yet visible
        val assignment = PartitionAssigner.assign(partitionCount, workers)
        // Persist the new mapping. Map is rendered as `pod=0,1,2` lines so the YAML
        // shape stays human-readable without bringing a YAML library into this path.
        val rendered = assignment.entries.sortedBy { it.key }
            .joinToString(separator = "\n") { (worker, parts) ->
                "$worker=${parts.sorted().joinToString(",")}"
            }
        val cm = buildConfigMap(
            name = assignmentName(scenarioName),
            role = "assignment",
            data = mapOf(
                KEY_ASSIGNMENT to rendered,
                KEY_LAST_UPDATED to Instant.now().toString(),
                KEY_LEADER to instanceId,
            ),
        )
        upsert(cm)
        // Take our own slice straight from the computed result; no need to read back.
        val newMine = assignment[instanceId] ?: emptySet()
        val prevMine = assignedRef.getAndSet(newMine)
        if (prevMine != newMine) {
            rebalances.merge(instanceId, 1L) { acc, _ -> acc + 1L }
        }
    }

    private fun readAssignmentFromCm() {
        val cm = client.configMaps().inNamespace(namespace)
            .withName(assignmentName(scenarioName)).get() ?: return
        val rendered = cm.data?.get(KEY_ASSIGNMENT) ?: return
        val mine = parseAssignment(rendered)[instanceId] ?: emptySet()
        assignedRef.set(mine)
    }

    private fun liveWorkers(): Set<String> {
        val cutoff = Instant.now().minus(staleAfter)
        val cms = client.configMaps().inNamespace(namespace)
            .withLabel(LABEL_APP, APP_VALUE)
            .withLabel(LABEL_SCENARIO, scenarioName)
            .withLabel(LABEL_ROLE, "status")
            .list().items
        return cms.mapNotNull { extractLiveWorker(it, cutoff) }.toSet()
    }

    private fun extractLiveWorker(cm: ConfigMap, cutoff: Instant): String? {
        val data = cm.data ?: return null
        val id = data[KEY_INSTANCE_ID] ?: return null
        val lastSeen = data[KEY_LAST_SEEN]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return if (lastSeen.isAfter(cutoff)) id else null
    }

    /**
     * Idempotent ConfigMap upsert: create if absent, otherwise carry the existing
     * resourceVersion and update. Avoids server-side-apply (the Fabric8 MockServer in CRUD
     * mode doesn't implement the apply PATCH variant) while keeping per-resource race
     * windows narrow — between get + create/update, two leaders could collide, but that's
     * already prevented by the lease ahead of this call.
     */
    private fun upsert(cm: ConfigMap) {
        val ops = client.resource(cm).inNamespace(namespace)
        val existing = client.configMaps().inNamespace(namespace).withName(cm.metadata.name).get()
        if (existing == null) {
            ops.create()
        } else {
            cm.metadata.resourceVersion = existing.metadata.resourceVersion
            ops.update()
        }
    }

    /**
     * Imperative ConfigMap construction. The fluent `ConfigMapBuilder` runs into a Kotlin
     * type-inference snag with Fabric8's recursive self-typed Fluent generics, so this
     * spells out the model objects directly. Equivalent result; less ceremony.
     */
    private fun buildConfigMap(name: String, role: String, data: Map<String, String>): ConfigMap {
        val labels = HashMap<String, String>(commonLabels(scenarioName)).also {
            it[LABEL_ROLE] = role
        }
        val meta = ObjectMeta().apply {
            this.name = name
            this.namespace = this@Coordinator.namespace
            this.labels = labels
        }
        return ConfigMap().apply {
            this.metadata = meta
            this.data = HashMap(data)
        }
    }

    internal fun parseAssignment(rendered: String): Map<String, Set<Int>> =
        rendered.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val worker = trimmed.substring(0, eq).trim()
            val parts = trimmed.substring(eq + 1).split(',')
                .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
                .toSet()
            worker to parts
        }.toMap()

    companion object {
        const val APP_VALUE: String = "data-generator"
        const val LABEL_APP: String = "app"
        const val LABEL_SCENARIO: String = "scenario"
        const val LABEL_ROLE: String = "role"

        const val KEY_INSTANCE_ID: String = "instanceId"
        const val KEY_LAST_SEEN: String = "lastSeen"
        const val KEY_ASSIGNMENT: String = "assignment"
        const val KEY_LAST_UPDATED: String = "lastUpdated"
        const val KEY_LEADER: String = "leader"

        fun leaseName(scenario: String): String = "dg-$scenario-leader"
        fun assignmentName(scenario: String): String = "dg-$scenario-assignment"
        fun statusName(scenario: String, instanceId: String): String = "dg-$scenario-status-$instanceId"
        fun commonLabels(scenario: String): Map<String, String> = mapOf(
            LABEL_APP to APP_VALUE,
            LABEL_SCENARIO to scenario,
        )

        private val NAMED_THREAD_FACTORY = java.util.concurrent.ThreadFactory { r ->
            Thread(r, "data-gen-coordinator").apply { isDaemon = true }
        }
    }
}
