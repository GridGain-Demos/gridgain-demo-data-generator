package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.BoundedKeySpaceSpec
import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.CountWarmupSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.NoWarmupSpec
import com.gridgain.demo.datagen.config.OperationKind
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TimeWarmupSpec
import com.gridgain.demo.datagen.config.UnboundedKeySpaceSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.metrics.MetricsRecorder
import com.gridgain.demo.datagen.observability.Instruments
import com.gridgain.demo.datagen.state.KeyRegistryState
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.TransactionOutcome
import com.gridgain.demo.datagen.target.WriteOutcome
import io.opentelemetry.api.common.Attributes
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val data: DataConfig,
    /**
     * One generator per worker thread; the size of this list **is** the run's concurrency.
     *
     * Modelled as a list rather than as a generator plus a separate `concurrency: Int` so the two
     * cannot disagree: a worker without its own generator would share a sequence cursor with
     * another and emit duplicate primary keys. Each generator is expected to have been built over
     * a [com.gridgain.demo.datagen.generation.workerStripe] so their key spaces are disjoint —
     * see `ScenarioRunnerCli.run`, which is what composes them.
     */
    private val generators: List<BusinessEventGenerator>,
    private val target: Target,
    private val untilStopCap: Duration = Duration.ofMinutes(1),
    private val decisionRandom: Random = Random(),
    private val keyRegistry: KeyRegistry = KeyRegistry(),
    private val instruments: Instruments = Instruments.noop(),
    private val targetName: String = "<unknown>",
    // Live throughput/latency counters. Defaults to a detached recorder — detached meaning no
    // LiveMetricsReporter publishes it, not that nothing is recorded. The run always reads its own
    // histogram back for [ScenarioResult.latency], so the percentiles in `result.yaml` do not
    // depend on the operator having configured a `metrics:` block and deployed a broker.
    private val metrics: MetricsRecorder = MetricsRecorder.detached(),
    /**
     * Pacing for the run, wrapped so an external command can override it mid-flight. Injectable
     * because the caller that wires the control channel needs a reference to the same instance
     * before [run] is entered — a command may arrive at any point, including immediately. Its
     * schedule clock starts at construction, so build it right before running.
     */
    val rateLimiter: ControllableRateLimiter =
        ControllableRateLimiter(buildRateLimiter(scenario)),
    /**
     * The run's shared stop signal — see [StopSignal]. Injectable because the sources that raise it
     * (the JVM shutdown hook, the control channel's `stop` command) are wired by the caller and
     * exist before the runner does. Defaults to a signal nothing raises, so a runner built without
     * one simply runs its configured duration.
     */
    private val stopSignal: StopSignal = StopSignal(),
) {

    private val totalAttempts = AtomicLong(0L)
    /** Reads that succeeded and found nothing. Shared across workers, so atomic. */
    private val readMisses = AtomicLong(0L)
    /** Row-level writes and the operations that produced them — see [ScenarioResult.rowsPerWrite]. */
    private val rowWrites = AtomicLong(0L)
    private val writeOps = AtomicLong(0L)
    @Volatile private var startedNanos: Long = 0L
    /** Captures the post-run registry contents for `state.yaml`. Safe to call multiple times. */
    fun keyRegistrySnapshot(): List<KeyRegistryState> = keyRegistry.snapshot()

    private val schemasByName: Map<String, SchemaSpec> = data.schemas.associateBy { it.name }
    private val keyColumnByName: Map<String, String> = data.schemas.associate { schema ->
        schema.name to (schema.columns.firstOrNull { it.key }?.name
            ?: throw MisconfigurationException(
                "schema '${schema.name}' has no column marked 'key: true'. " +
                "ScenarioRunner requires the KeyColumnValidator to have passed before construction."
            ))
    }

    /**
     * Turns a key-space index into the key the root schema actually uses — present only in bounded
     * mode, where the key space supersedes the key column's own value source so that a put and a get
     * can name the same row.
     */
    private val boundedKeys: BoundedKeyResolver? = when (val space = scenario.keySpace) {
        is UnboundedKeySpaceSpec -> null
        is BoundedKeySpaceSpec -> BoundedKeyResolver.of(
            space, scenario.rootSchemas.first(), schemasByName, keyColumnByName,
        )
    }

    // ---- Warmup window -----------------------------------------------------
    // The boundary is crossed exactly once, by whichever worker observes it first. `measuredFrom`
    // and `warmupAttempts` are written under that one-shot CAS and read only after the workers have
    // joined, so a plain volatile pair would do; atomics keep the write and the guard together.
    private val measurementBegun = AtomicBoolean(false)
    private val measurementStartedNanos = AtomicLong(0L)
    private val attemptsAtMeasurementStart = AtomicLong(0L)

    init {
        require(generators.isNotEmpty()) {
            "ScenarioRunner needs at least one generator: the list's size is the run's " +
                "concurrency, and a run with no workers would do nothing. Pass one generator " +
                "per worker thread."
        }
        // A generator paused at rate 0.0 is parked inside the limiter, not in the loop, so it would
        // never reach the stop check below. Pushing the release to it is what lets a paused fleet be
        // stopped at all.
        stopSignal.onRaise(rateLimiter::release)
    }

    fun run(): ScenarioResult {
        val evaluator = StopConditionEvaluator(scenario.stopConditions, stopSignal)
        instruments.targetRateRef.set(rateLimiter.currentTargetTps())
        totalAttempts.set(0L)
        startedNanos = System.nanoTime()
        val started = Instant.now()
        // Atomic because every worker thread reports into them and the duration predicate below
        // reads them. The first worker to reach a stop wins and the rest observe it and finish.
        val success = AtomicLong(0L)
        val error = AtomicLong(0L)
        val stopReason = AtomicReference("")

        // The three duration kinds differ only in when they are exhausted, so they share one loop.
        // They used to have one loop each, which was fine while the only early exit was the stop
        // condition — the stop *signal* has to end a run of **every** kind (a SIGTERM during a
        // `time` or `count` run must stop it cleanly too), and per-kind loops would mean three
        // copies of that check drifting apart.
        val notExhausted: () -> Boolean
        val exhaustedReason: String
        when (val d = scenario.duration) {
            is CountDurationSpec -> {
                // With N workers this can overshoot by up to N-1: several may pass the check
                // before any of them records its outcome. Bounding it exactly would mean
                // reserving a slot per operation, which puts a contended atomic ahead of every
                // tick to buy precision no load test asks for.
                notExhausted = { success.get() + error.get() < d.value }
                exhaustedReason = "count reached"
            }
            is TimeDurationSpec -> {
                val td = Duration.parse(d.value)
                notExhausted = { Duration.between(started, Instant.now()) < td }
                exhaustedReason = "time elapsed"
            }
            is UntilStopDurationSpec -> {
                // `external_signal` is the scenario's explicit declaration that it is intentionally
                // unbounded and ends when an operator says so, so [untilStopCap] must not apply to
                // it — capping a "run until stopped" scenario would silently mean "run for a
                // minute". Without that declaration the cap still bounds a scenario whose conditions
                // might never trigger; ExternalSignalControlValidator warns about that shape.
                notExhausted =
                    if (scenario.stopConditions.any { it is ExternalSignalStopSpec }) {
                        { true }
                    } else {
                        { Duration.between(started, Instant.now()) < untilStopCap }
                    }
                exhaustedReason = "until_stop_condition cap reached"
            }
        }

        if (generators.size == 1) {
            // Deliberately inline rather than a one-element thread pool: a single-worker run must
            // stay exactly what it was before concurrency existed, on the caller's own thread.
            workerLoop(generators[0], evaluator, notExhausted, success, error, stopReason)
        } else {
            val workers = generators.mapIndexed { index, gen ->
                Thread(
                    { workerLoop(gen, evaluator, notExhausted, success, error, stopReason) },
                    "datagen-worker-$index",
                )
            }
            workers.forEach { it.start() }
            // run() must not return while a worker is still writing: the caller closes the target
            // and flushes the final metrics snapshot the moment it does.
            workers.forEach { it.join() }
        }
        stopReason.compareAndSet("", exhaustedReason)

        val endedNanos = System.nanoTime()
        val wall = Duration.between(started, Instant.now())
        val successes = success.get()
        val errors = error.get()

        // Throughput and latency both describe the **measured window** — everything after the
        // warmup closed. A rate computed over the whole run would be dragged down by exactly the
        // operations the warmup exists to exclude, and would disagree with the percentiles beside
        // it. With `warmup: {kind: none}` the window is the whole run and this is a no-op.
        val measuredOps = (successes + errors) - attemptsAtMeasurementStart.get()
        val measuredNanos =
            if (measurementBegun.get()) endedNanos - measurementStartedNanos.get() else 0L
        val achievedRate = if (measuredNanos > 0)
            measuredOps.toDouble() / (measuredNanos / 1_000_000_000.0) else 0.0

        return ScenarioResult(
            scenarioName = scenario.name,
            achievedRate = achievedRate,
            errorCount = errors,
            successCount = successes,
            stopReason = stopReason.get(),
            wallTime = wall,
            readMissCount = readMisses.get(),
            rowsWritten = rowWrites.get(),
            rowsPerWrite = writeOps.get().let { if (it > 0) rowWrites.get().toDouble() / it else 0.0 },
            measuredOperations = measuredOps,
            measuredWindow = Duration.ofNanos(measuredNanos),
            // Read unconditionally. [metrics] defaults to a detached recorder, but detached means
            // "no reporter is publishing this", not "nothing is recorded" — the histogram is fed by
            // every operation either way. Summarising it here is what makes a run quotable from its
            // own results file rather than only from a live Kafka feed.
            latency = LatencySummary.from(metrics.measuredSnapshot()),
        )
    }

    /**
     * One worker's loop. Every worker runs this against its own [generator] and the run's shared
     * limiter, evaluator, registry and counters.
     *
     * A worker stops when the duration is exhausted, when the stop signal is raised, when a stop
     * condition triggers, or when **another worker** has already recorded a reason — that last
     * check is what makes one worker's stop end the whole run rather than only itself.
     */
    private fun workerLoop(
        generator: BusinessEventGenerator,
        evaluator: StopConditionEvaluator,
        notExhausted: () -> Boolean,
        success: AtomicLong,
        error: AtomicLong,
        stopReason: AtomicReference<String>,
    ) {
        while (notExhausted() && stopReason.get().isEmpty()) {
            // Read before the tick rather than after it, so a signal raised mid-tick lets that tick
            // finish and costs no further operation.
            val signalled = stopSignal.reason()
            if (signalled != null) {
                // compareAndSet, not set: the first reason recorded is the one that ended the run,
                // and a later worker noticing the same signal must not overwrite it.
                stopReason.compareAndSet("", "$STOPPED_BY_SIGNAL$signalled")
                break
            }
            val s = tick(generator, rateLimiter, evaluator)
            if (s) success.incrementAndGet() else error.incrementAndGet()
            val triggered = evaluator.shouldStop()
            if (triggered != null) { stopReason.compareAndSet("", triggered); break }
        }
    }

    private fun tick(
        generator: BusinessEventGenerator,
        rateLimiter: RateLimiter,
        evaluator: StopConditionEvaluator,
    ): Boolean {
        rateLimiter.acquire()
        // Before the operation, so the one that crosses the boundary is measured rather than
        // discarded — and so a `time` warmup ends on the clock rather than on the next op's latency.
        closeWarmupIfDue()
        // Re-published every tick because the target moves: a ramp/step walks it, and the control
        // channel can override it at any moment. One atomic store is nothing next to the round trip
        // below, and it keeps the OTel gauge honest for the Grafana requested-vs-achieved panel.
        instruments.targetRateRef.set(rateLimiter.currentTargetTps())
        val rootSchemaName = scenario.rootSchemas.first()  // multi-root weighting deferred
        val rootKeyColumn = keyColumnByName[rootSchemaName]!!
        val kind = chooseOperation(rootSchemaName)
        val attrs = instruments.opAttributes(scenario.name, targetName, rootSchemaName, kind.metricLabel)

        instruments.inFlight.add(1, attrs)
        val t0 = System.nanoTime()
        var transactionOutcome: TransactionOutcome = TransactionOutcome.NONE
        val success: Boolean = try {
            when (kind) {
                OperationKind.GET -> performRead(rootSchemaName, readKey(rootSchemaName), attrs)
                OperationKind.PUT -> {
                    val outcome = performWrite(generator, rootSchemaName, rootKeyColumn, key = null)
                    transactionOutcome = outcome.transactionOutcome
                    outcome.success
                }
                // The read-modify-write `PutGetBenchmark` measures: one key, read then written,
                // counted as a single operation. Deliberately not two ops — the pair is what an
                // application actually performs, and splitting it would report twice the throughput
                // at half the latency for the same work.
                OperationKind.PUT_GET -> {
                    val key = readKey(rootSchemaName)
                    val read = performRead(rootSchemaName, key, attrs)
                    val outcome = performWrite(generator, rootSchemaName, rootKeyColumn, key = key)
                    transactionOutcome = outcome.transactionOutcome
                    read && outcome.success
                }
            }
        } catch (e: Exception) {
            instruments.opErrors.add(1, attrs.toBuilder()
                .put(Instruments.ATTR_EXCEPTION, e.javaClass.simpleName).build())
            false
        } finally {
            instruments.inFlight.add(-1, attrs)
        }
        val latencyNanos = System.nanoTime() - t0
        // Same latency the OTel histogram gets — the wall time of the target operation (the
        // GridGain write/read), which is the "execution latency" the live graph reports.
        metrics.record(latencyNanos = latencyNanos, success = success)
        instruments.opLatency.record(latencyNanos.toDouble(), attrs)
        instruments.opCount.add(1, attrs)
        if (!success) instruments.opErrors.add(1, attrs.toBuilder()
            .put(Instruments.ATTR_EXCEPTION, "TargetReportedFailure").build())

        // F12: emit tx_commit / tx_rollback as separate op-tagged points alongside the
        // underlying put. Latency for the tx op is the wall time of the wrapped event —
        // a meaningful proxy for "how long committed/rolled-back transactions take" in
        // aggregate. The error counter for a rollback is already recorded above on op=put,
        // so we don't double-count exceptions here.
        when (transactionOutcome) {
            TransactionOutcome.COMMITTED -> emitTxOp(rootSchemaName, "tx_commit", latencyNanos)
            TransactionOutcome.ROLLED_BACK -> emitTxOp(rootSchemaName, "tx_rollback", latencyNanos)
            TransactionOutcome.NONE -> { /* no transaction wrap; nothing to emit */ }
        }
        evaluator.recordOutcome(success = success, latencyNanos = latencyNanos)

        val attempts = totalAttempts.incrementAndGet()
        val elapsedSec = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        if (elapsedSec > 0) instruments.observedRateRef.set(attempts / elapsedSec)
        return success
    }

    /**
     * Pick this tick's operation, then fall back to a put if it cannot be performed.
     *
     * Two things can make a read impossible: a target that cannot read at all, and — in unbounded
     * mode only — a key registry that is still empty, because the only keys a read can name there
     * are ones this process has already written. Bounded mode has neither problem, which is a large
     * part of why it exists.
     *
     * ⚠️ A target that cannot read, paired with a nonzero `get` weight, silently becomes 100% puts
     * here. That was true of `read_ratio` before it and is carried forward unchanged; a third target
     * kind that cannot read must reinstate an explicit misconfiguration error rather than let this
     * absorb the mismatch.
     */
    private fun chooseOperation(rootSchemaName: String): OperationKind {
        val chosen = scenario.operations.choose(decisionRandom)
        if (chosen == OperationKind.PUT) return chosen
        val canRead = target.supportsReads &&
            (boundedKeys != null || keyRegistry.size(rootSchemaName) > 0)
        return if (canRead) chosen else OperationKind.PUT
    }

    /** Bounded mode draws from the key space; unbounded mode samples what this run has written. */
    private fun readKey(rootSchemaName: String): Any =
        boundedKeys?.nextKey(decisionRandom)
            ?: keyRegistry.sample(rootSchemaName, decisionRandom)!!

    private fun performRead(rootSchemaName: String, key: Any, attrs: Attributes): Boolean {
        val outcome = target.read(rootSchemaName, key)
        // A successful read that found nothing is a miss, and it has to be visible. Only the flag
        // used to be looked at, so a run whose every get missed reported a healthy rate and a clean
        // error count — a benchmark of how fast the cluster can say "no". It is deliberately not an
        // error: a sparse key space is not a broken cluster.
        if (outcome.success && outcome.value == null) {
            readMisses.incrementAndGet()
            instruments.opMisses.add(1, attrs)
        }
        return outcome.success
    }

    /**
     * Generate an event and write it. [key] forces the parent key, which is how the put half of a
     * `put_get` lands on the row the get half just read, and how bounded mode addresses its space.
     */
    private fun performWrite(
        generator: BusinessEventGenerator,
        rootSchemaName: String,
        rootKeyColumn: String,
        key: Any?,
    ): WriteOutcome {
        val event = generator.next()
        val rootSchema = schemasByName[rootSchemaName]!!
        val forcedKey = key ?: boundedKeys?.nextKey(decisionRandom)
        val finalEvent = if (forcedKey != null) {
            event.copy(parentRow = LinkedHashMap(event.parentRow).also { it[rootKeyColumn] = forcedKey })
        } else {
            maybeApplyUpdate(event, rootSchema, rootKeyColumn)
        }
        val parentKey = finalEvent.parentRow[rootKeyColumn]!!
        keyRegistry.register(rootSchemaName, parentKey)
        finalEvent.childrenBySchema.forEach { (childSchema, rows) ->
            val childKeyColumn = keyColumnByName[childSchema] ?: return@forEach
            rows.forEach { row -> row[childKeyColumn]?.let { keyRegistry.register(childSchema, it) } }
        }
        // One operation is one business event, which may be many rows across several caches. Counted
        // rather than derived, because cohort buckets make the fan-out vary per event. Without it
        // `achieved_rate` looks like puts/sec while counting events, and two runs over different
        // data.yaml files are silently incomparable.
        rowWrites.addAndGet(1L + finalEvent.childrenBySchema.values.sumOf { it.size })
        writeOps.incrementAndGet()
        return target.write(finalEvent)
    }

    /**
     * Close the warmup window the first time a worker sees it end.
     *
     * Checked before the operation rather than after it, so the operation that crosses the boundary
     * is measured rather than discarded. `update_ratio`'s substitution is skipped in bounded mode
     * for the same reason bounded mode exists at all — the key space already decides which row is
     * written, and layering a second key-reuse rule on top would make the distribution unreportable.
     */
    private fun closeWarmupIfDue() {
        if (measurementBegun.get()) return
        val done = when (val w = scenario.warmup) {
            is NoWarmupSpec -> true
            is CountWarmupSpec -> totalAttempts.get() >= w.value
            is TimeWarmupSpec -> System.nanoTime() - startedNanos >= Duration.parse(w.value).toNanos()
        }
        if (done && measurementBegun.compareAndSet(false, true)) {
            measurementStartedNanos.set(System.nanoTime())
            attemptsAtMeasurementStart.set(totalAttempts.get())
            metrics.beginMeasurement()
        }
    }

    private fun emitTxOp(schemaName: String, op: String, latencyNanos: Long) {
        val txAttrs = instruments.opAttributes(scenario.name, targetName, schemaName, op)
        instruments.opLatency.record(latencyNanos.toDouble(), txAttrs)
        instruments.opCount.add(1, txAttrs)
    }

    private fun maybeApplyUpdate(event: BusinessEvent, rootSchema: SchemaSpec, keyColumn: String): BusinessEvent {
        if (rootSchema.updateRatio > 0.0 &&
            keyRegistry.size(rootSchema.name) > 0 &&
            decisionRandom.nextDouble() < rootSchema.updateRatio
        ) {
            val existingKey = keyRegistry.sample(rootSchema.name, decisionRandom)!!
            val newParent = LinkedHashMap(event.parentRow)
            newParent[keyColumn] = existingKey
            return event.copy(parentRow = newParent)
        }
        return event
    }

    companion object {
        /**
         * Prefix on the `stop_reason` of a run that a [StopSignal] ended, so a consumer reading
         * `result.yaml` or the run log can tell "somebody stopped this" from "it finished its
         * configured duration" (`count reached` / `time elapsed` / `until_stop_condition cap
         * reached`) and from a triggered stop condition. The signal's own reason follows it.
         */
        const val STOPPED_BY_SIGNAL = "stopped by signal: "

        /** Builds the limiter for the scenario's configured rate schedule. Public so a caller that
         *  needs the [ControllableRateLimiter] wrapper before constructing the runner can build the
         *  same thing the default would have. */
        fun buildRateLimiter(scenario: ScenarioSpec): RateLimiter = when (val r = scenario.rate) {
            is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
            is RampedRateSpec -> RampedRateLimiter(
                fromOpsPerSecond = r.from, toOpsPerSecond = r.to,
                rampDuration = Duration.parse(r.over),
            )
            is SteppedRateSpec -> SteppedRateLimiter(
                steps = r.steps.map { StepConfig(rate = it.rate, hold = Duration.parse(it.hold)) },
            )
        }

        /** The scenario's initial target rate (ops/sec): the constant rate, a ramp's start
         *  value, or the first step. */
        fun configuredStartRate(scenario: ScenarioSpec): Double = when (val r = scenario.rate) {
            is ConstantRateSpec -> r.opsPerSecond
            is RampedRateSpec -> r.from
            is SteppedRateSpec -> r.steps.first().rate
        }
    }
}
