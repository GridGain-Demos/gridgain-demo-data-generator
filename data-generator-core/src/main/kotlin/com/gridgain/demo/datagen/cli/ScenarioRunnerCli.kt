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
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.RunHistoryEntry
import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.Target
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.opentelemetry.api.OpenTelemetry
import net.datafaker.Faker
import org.slf4j.LoggerFactory
import java.time.Instant

object ScenarioRunnerCli {

    data class Resolution(
        val parsedConfig: ParsedConfiguration,
        val scenario: ScenarioSpec,
        val targetSpec: TargetSpec,
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

        val targetSpec = parsedConfig.ops.targets.firstOrNull { it.name == scenario.target }
            ?: throw MisconfigurationException(
                "Target '${scenario.target}' referenced by scenario '${scenario.name}' " +
                "is not declared in ops.yaml's targets[]."
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
            targetSpec = targetSpec,
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
        val metricsRecorder = MetricsRecorder.detached()
        // Both are built further down, once setup is complete — see the comment at their site.
        var metricsReporter: LiveMetricsReporter? = null
        var controlListener: ControlListener? = null

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
                    logger.lifecycle("live metrics: publishing to Kafka topic '${m.topic}' every ${m.intervalMs}ms")
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
                targetName = resolution.targetSpec.name,
                metrics = metricsRecorder,
                rateLimiter = rateLimiter,
            )

            runLog.emit(LifecycleEvent.ScenarioStarted(
                scenarioName = resolution.scenario.name,
                targetName = resolution.targetSpec.name,
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
            // Control first: stop accepting commands before the run's machinery goes away, so a
            // late command can't set a rate on a limiter nothing is reading any more.
            runCatching { controlListener?.close() }
            // Then the reporter, so it flushes a final inactive snapshot (observedTps→0),
            // letting a live consumer see the run end / a stepped-rate restart cleanly.
            runCatching { metricsReporter?.close() }
            runCatching { resolution.coordinator?.stop() }
            OtelInitializer.close(resolution.openTelemetry)
        }
    }
}
