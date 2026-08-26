package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.config.ParsedConfiguration
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.TargetSpec
import com.gridgain.demo.datagen.control.ControlListener
import com.gridgain.demo.datagen.control.KafkaControlListener
import com.gridgain.demo.datagen.coordinator.Coordinator
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import com.gridgain.demo.datagen.metrics.KafkaMetricsSink
import com.gridgain.demo.datagen.metrics.LatencyHistogramBounds
import com.gridgain.demo.datagen.metrics.LiveMetricsReporter
import com.gridgain.demo.datagen.metrics.MetricsRecorder
import com.gridgain.demo.datagen.observability.Instruments
import com.gridgain.demo.datagen.observability.LifecycleEvent
import com.gridgain.demo.datagen.observability.OtelInitializer
import com.gridgain.demo.datagen.observability.RunLog
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.runtime.RunId
import com.gridgain.demo.datagen.scenario.ControllableRateLimiter
import com.gridgain.demo.datagen.scenario.KeyRegistry
import com.gridgain.demo.datagen.scenario.ScenarioResult
import com.gridgain.demo.datagen.scenario.ScenarioRunner
import com.gridgain.demo.datagen.scenario.StopSignal
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.RunHistoryEntry
import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.Target
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.opentelemetry.api.OpenTelemetry
import net.datafaker.Faker
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScenarioRunnerCli {

    data class Resolution(
        val parsedConfig: ParsedConfiguration,
        val scenario: ScenarioSpec,
        /**
         * The cluster named by `--target-cluster`. Deliberately a name, not a [TargetSpec]: core
         * cannot know which flavour to build, and each `Main` builds the only one it can serve.
         */
        val targetClusterName: String,
        val keyColumnByName: Map<String, String>,
        val openTelemetry: OpenTelemetry,
        val instruments: Instruments,
        /**
         * Distributed-mode orchestrator. Non-null when the scenario declares a `distribution:`
         * block AND the process is running in-cluster (POD_NAME / POD_NAMESPACE present in
         * the env). Null for single-pod scenarios and for local dev runs of a distributed
         * scenario.
         */
        val coordinator: Coordinator?,
        val pendingEvents: MutableList<LifecycleEvent> = mutableListOf(),
    )

    /** Factory hook: tests inject a stub to bypass the real K8s client. */
    fun interface CoordinatorFactory {
        fun build(scenario: ScenarioSpec, env: (String) -> String?, logger: DataGenLogger): Coordinator?
    }

    fun defaultLogger(): DataGenLogger =
        Slf4jDataGenLogger(LoggerFactory.getLogger("datagen-cli"))

    fun resolve(
        parsed: CliArgs,
        logger: DataGenLogger,
        coordinatorFactory: CoordinatorFactory = DefaultCoordinatorFactory,
    ): Resolution {
        System.setProperty("gg.demo.client.endpoints", parsed.clusterEndpoints.toAbsolutePath().toString())

        val parser = ConfigurationParser(logger = logger)
        val parsedConfig = parser.parse(parsed.dataFile.toFile(), parsed.opsFile.toFile())

        val scenario = parsedConfig.ops.scenarios.firstOrNull { it.name == parsed.scenarioName }
            ?: throw MisconfigurationException(
                "Scenario '${parsed.scenarioName}' not declared in ops.yaml. " +
                "Available: ${parsedConfig.ops.scenarios.joinToString(", ") { it.name }}."
            )

        val keyColumnByName = parsedConfig.data.schemas.associate { schema ->
            schema.name to schema.columns.first { it.key }.name
        }

        val effectiveOtelSpec = applyEndpointOverride(parsedConfig.ops.otel, parsed.otelEndpointOverride, logger)
        val openTelemetry = OtelInitializer.fromSpec(effectiveOtelSpec, logger)
        val instruments = Instruments(openTelemetry)
        val coordinator = coordinatorFactory.build(scenario, System::getenv, logger)

        return Resolution(
            parsedConfig = parsedConfig,
            scenario = scenario,
            targetClusterName = parsed.targetCluster,
            keyColumnByName = keyColumnByName,
            openTelemetry = openTelemetry,
            instruments = instruments,
            coordinator = coordinator,
        )
    }

    /**
     * Default [CoordinatorFactory]. Returns a Coordinator only when both conditions hold:
     * 1. The scenario declares a `distribution:` block (multi-pod mode requested).
     * 2. POD_NAME and POD_NAMESPACE env vars are set (we're running inside a k8s Pod
     *    that was launched via the data-generator-distributed Deployment manifest).
     *
     * Local-laptop runs of a scenario that happens to have `distribution:` configured will
     * log a warning and run in single-pod mode rather than failing the run; this matches
     * how `--otel-endpoint-override` behaves when its prerequisite is missing.
     */
    internal object DefaultCoordinatorFactory : CoordinatorFactory {
        override fun build(
            scenario: ScenarioSpec, env: (String) -> String?, logger: DataGenLogger,
        ): Coordinator? {
            val distribution = scenario.distribution ?: return null
            val podName = env("POD_NAME")
            val podNamespace = env("POD_NAMESPACE")
            if (podName.isNullOrBlank() || podNamespace.isNullOrBlank()) {
                logger.warn(
                    "scenario '${scenario.name}' declares distribution:{replicas=${distribution.replicas}, " +
                        "partition_count=${distribution.partitionCount}} but POD_NAME/POD_NAMESPACE are unset. " +
                        "Falling back to single-pod execution — set those env vars (k8s downward API) to enable " +
                        "distributed mode."
                )
                return null
            }
            return Coordinator(
                client = KubernetesClientBuilder().build(),
                namespace = podNamespace,
                scenarioName = scenario.name,
                instanceId = podName,
                partitionCount = distribution.partitionCount,
            )
        }
    }

    /**
     * F13: when `--otel-endpoint-override` is supplied (plugin-driven runs inheriting from
     * a deployed Prometheus/Grafana monitor), it wins over `ops.otel.endpoint`. If the
     * user's `ops.otel.exporter` was `NONE`, the override implicitly upgrades it to `OTLP`
     * — passing an override only makes sense if metrics should be exported. Standalone
     * runs leave `override = null` and `ops.otel` flows through unchanged.
     */
    internal fun applyEndpointOverride(
        opsOtel: OtelSpec,
        override: String?,
        logger: DataGenLogger,
    ): OtelSpec {
        if (override.isNullOrBlank()) return opsOtel
        val effectiveExporter = if (opsOtel.exporter == OtelExporter.NONE) OtelExporter.OTLP else opsOtel.exporter
        if (opsOtel.exporter == OtelExporter.NONE) {
            logger.lifecycle("otel: --otel-endpoint-override='$override' supplied; promoting exporter NONE → OTLP.")
        } else if (opsOtel.endpoint != null && opsOtel.endpoint != override) {
            logger.lifecycle("otel: --otel-endpoint-override='$override' overrides ops.otel.endpoint='${opsOtel.endpoint}'.")
        } else {
            logger.lifecycle("otel: applying --otel-endpoint-override='$override'.")
        }
        return opsOtel.copy(exporter = effectiveExporter, endpoint = override)
    }

    fun run(parsed: CliArgs, resolution: Resolution, target: Target, logger: DataGenLogger): ScenarioResult {
        val layout = OutputLayout(parsed.outputDir)
        layout.ensureBaseDirectories()

        val runId = RunId.generate()
        val runLog = RunLog(
            runLogFile = layout.runLogFile(runId),
            otelLogger = resolution.openTelemetry.logsBridge.get(Instruments.SCOPE),
        )
        resolution.pendingEvents.forEach { runLog.emit(it) }
        resolution.pendingEvents.clear()

        // Live throughput/latency counters. The runner feeds per-op latency in; the reporter (wired
        // below, opt-in via ops.yaml `metrics:`) publishes snapshots for external consumers. Absent
        // metrics block => the recorder is a harmless no-op nobody reads.
        // The histogram bounds come from the metrics block because that is the only case where the
        // histogram is read: a detached recorder's bounds are the shape of an object with no reader,
        // not a configuration default. See LatencyHistogramBounds.detached.
        val metricsRecorder = resolution.parsedConfig.ops.metrics
            ?.let {
                MetricsRecorder(
                    LatencyHistogramBounds(
                        highestMs = it.histogramHighestMs,
                        significantDigits = it.histogramSignificantDigits,
                    )
                )
            }
            ?: MetricsRecorder.detached()
        // Both are built further down, once setup is complete — see the comment at their site.
        var metricsReporter: LiveMetricsReporter? = null
        var controlListener: ControlListener? = null

        // One stop signal for the whole run, raised by the shutdown hook below. Registered here,
        // where both `Gg8Main` and `Gg9Main` converge, so neither entry point can be given a
        // graceful stop the other lacks.
        val stopSignal = StopSignal()
        // Counted down once the end-of-run path has finished — final `active=false` metrics
        // snapshot, target close, result and state files — whether it succeeded or threw.
        val runComplete = CountDownLatch(1)
        val shutdownHook = Thread({
            stopSignal.raise("SIGTERM (graceful shutdown requested)")
            // The hook must not race the main thread to exit. A JVM in shutdown halts as soon as
            // its hooks return and does **not** wait for other threads, so returning here before
            // the run has flushed its final snapshot and closed its target would reproduce exactly
            // the "a killed process emits nothing" behaviour this hook exists to remove.
            if (!runComplete.await(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                runCatching {
                    logger.warn(
                        "graceful stop: the run did not finish within ${GRACEFUL_STOP_SECONDS}s of " +
                            "SIGTERM; exiting anyway. The final metrics snapshot and result.yaml may " +
                            "be missing — a consumer must fall back to the last live metrics tick."
                    )
                }
            }
        }, "datagen-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            resolution.coordinator?.let { coord ->
                coord.start()
                logger.lifecycle(
                    "distributed mode: Coordinator started (scenario='${resolution.scenario.name}', " +
                        "partition_count=${resolution.scenario.distribution?.partitionCount}). " +
                        "Initial lease/assignment will settle within a few seconds."
                )
            }
            val persister = StatePersister()
            val loadedState: GeneratorState? = persister.load(layout.stateFile)
            if (loadedState != null) {
                logger.lifecycle(
                    "loaded prior state: ${loadedState.sequences.size} sequence cursors, " +
                    "${loadedState.keys.sumOf { it.keys.size }} keys, " +
                    "${loadedState.runHistory.size} prior runs (from ${layout.stateFile})."
                )
            }

            val partitionStripe = resolution.coordinator?.derivePartitionStripeLocally()
            if (partitionStripe != null) {
                logger.lifecycle(
                    "distributed mode: this pod owns partition stripe " +
                        "${partitionStripe.partitionId}/${partitionStripe.partitionCount} " +
                        "(sequences will stride by ${partitionStripe.partitionCount}*step)."
                )
            }
            val factory = ValueSourceFactory(
                yamlDataRoot = parsed.dataFile.parent,
                seed = 0L,
                loadedState = loadedState,
                partitionStripe = partitionStripe,
            )
            val rootSchema = resolution.scenario.rootSchemas.first()
            val gen = BusinessEventGenerator(
                data = resolution.parsedConfig.data,
                rootSchemaName = rootSchema,
                factory = factory,
                faker = Faker(),
                cohortSeed = 0L,
            )

            val keyRegistry = KeyRegistry()
            if (loadedState != null) keyRegistry.restore(loadedState.keys)

            // Built here, not earlier: a ramped/stepped schedule starts its clock at construction,
            // so the limiter must not exist while provisioning and state loading are still running.
            val rateLimiter = ControllableRateLimiter(
                ScenarioRunner.buildRateLimiter(resolution.scenario)
            )

            // Live throughput/latency export (opt-in via ops.yaml `metrics:`): a snapshot ~1s to a
            // Kafka topic for external consumers (e.g. the demo UI), so it works whether the
            // generator runs in-cluster, local, or on a host. targetTps is read through the limiter
            // so the snapshot tracks a ramp, a step, or a live override rather than the start rate.
            metricsReporter = resolution.parsedConfig.ops.metrics?.let { m ->
                LiveMetricsReporter(
                    recorder = metricsRecorder,
                    sink = KafkaMetricsSink(bootstrapServers = m.kafkaBootstrap, topic = m.topic),
                    targetTps = rateLimiter::currentTargetTps,
                    runGroup = parsed.runGroup,
                    runId = runId,
                    intervalMs = m.intervalMs,
                ).also {
                    it.start()
                    logger.lifecycle(
                        "live metrics: publishing to Kafka topic '${m.topic}' every ${m.intervalMs}ms " +
                            "(latency histogram: ${m.histogramHighestMs}ms ceiling, " +
                            "${m.histogramSignificantDigits} significant digits)"
                    )
                }
            }

            // Runtime rate control (opt-in via ops.yaml `control:`): lets an external driver raise
            // and lower load without restarting the run. Started before run() so a command that
            // arrives immediately is not missed.
            controlListener = resolution.parsedConfig.ops.control?.let { c ->
                KafkaControlListener(
                    bootstrapServers = c.kafkaBootstrap,
                    topic = c.topic,
                    runGroup = parsed.runGroup,
                    runId = runId,
                    setRate = rateLimiter::setRate,
                ).also {
                    it.start()
                    logger.lifecycle(
                        "runtime control: listening on Kafka topic '${c.topic}' for run group " +
                            "'${parsed.runGroup}'"
                    )
                }
            }

            val runner = ScenarioRunner(
                scenario = resolution.scenario,
                data = resolution.parsedConfig.data,
                generator = gen,
                target = target,
                keyRegistry = keyRegistry,
                instruments = resolution.instruments,
                targetName = resolution.targetClusterName,
                metrics = metricsRecorder,
                rateLimiter = rateLimiter,
                stopSignal = stopSignal,
            )

            runLog.emit(LifecycleEvent.ScenarioStarted(
                scenarioName = resolution.scenario.name,
                targetName = resolution.targetClusterName,
            ))

            val startedAt = Instant.now()
            val result = runner.run()
            val completedAt = Instant.now()
            if (target is AutoCloseable) target.close()

            runLog.emit(LifecycleEvent.ScenarioStopped(
                scenarioName = resolution.scenario.name,
                reason = result.stopReason,
                successCount = result.successCount,
                errorCount = result.errorCount,
            ))

            val resultFile = layout.resultFile(runId)
            ScenarioResult.write(result, resultFile)

            val newState = GeneratorState(
                schemaVersion = CURRENT_STATE_SCHEMA_VERSION,
                sequences = factory.snapshotSequences(),
                keys = runner.keyRegistrySnapshot(),
                runHistory = (loadedState?.runHistory ?: emptyList()) + RunHistoryEntry(
                    runId = runId,
                    scenarioName = resolution.scenario.name,
                    startedAt = startedAt.toString(),
                    completedAt = completedAt.toString(),
                    successCount = result.successCount,
                    errorCount = result.errorCount,
                    stopReason = result.stopReason,
                ),
            )
            val coord = resolution.coordinator
            if (coord == null || coord.isLeader()) {
                StatePersister(writable = true).save(newState, layout.stateFile)
                runLog.emit(LifecycleEvent.StatePersisted(
                    stateFile = layout.stateFile,
                    sequenceCount = newState.sequences.size,
                    keyCount = newState.keys.sumOf { it.keys.size },
                    runHistorySize = newState.runHistory.size,
                ))
            } else {
                logger.lifecycle(
                    "distributed mode (follower): skipping state persistence; the leader pod owns " +
                        "the on-disk state.yaml write."
                )
            }

            logger.lifecycle(
                "scenario '${resolution.scenario.name}' complete: ${result.successCount} successes, " +
                "${result.errorCount} errors, achieved_rate=${result.achievedRate}, " +
                "stop_reason=${result.stopReason}"
            )
            logger.lifecycle("result written to $resultFile")
            logger.lifecycle("state written to ${layout.stateFile}")

            return result
        } finally {
            try {
                // Control first: stop accepting commands before the run's machinery goes away, so a
                // late command can't set a rate on a limiter nothing is reading any more.
                runCatching { controlListener?.close() }
                // Then the reporter, so it flushes a final inactive snapshot (observedTps→0),
                // letting a live consumer see the run end / a stepped-rate restart cleanly.
                runCatching { metricsReporter?.close() }
                runCatching { resolution.coordinator?.stop() }
                OtelInitializer.close(resolution.openTelemetry)
            } finally {
                // Releases the shutdown hook: everything a clean stop has to emit has now been
                // emitted, so the JVM may halt. In an inner `finally` so a failing cleanup step
                // cannot leave the hook sitting out its whole wait on the way to a crash.
                runComplete.countDown()
                // Throws once a shutdown is already under way (the SIGTERM case), where there is
                // nothing to remove — the hook is the thread running.
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }
    }

    /**
     * How long the shutdown hook waits for the run to stop cleanly before letting the JVM halt.
     *
     * Sized against the two deadlines that actually kill the process, and set comfortably inside
     * the tighter one:
     * - a Kubernetes pod gets `terminationGracePeriodSeconds` before SIGKILL, and the generator's
     *   Deployment does not set it — so it is the 30s default;
     * - the host systemd unit gets `TimeoutStopSec`, rendered from the toolkit's
     *   `host_timeouts.unit_active` (120s in the shipped template).
     *
     * 10s leaves 20s of margin under the tighter of the two. What has to fit inside it is small and
     * individually bounded: one in-flight target operation, the result and state files, then the
     * metrics reporter's final `active=false` snapshot — itself bounded by a 2s thread join, a 2s
     * Kafka `max.block.ms` and a 2s producer close. Overrunning the pod's grace period means
     * SIGKILL and no final snapshot at all — the behaviour this hook exists to remove — so the
     * bound deliberately stops well short of the deadline instead of trying to spend all of it.
     */
    internal const val GRACEFUL_STOP_SECONDS: Long = 10L
}
