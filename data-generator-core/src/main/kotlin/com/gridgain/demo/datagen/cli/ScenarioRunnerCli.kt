package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.config.ParsedConfiguration
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.TargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import com.gridgain.demo.datagen.observability.Instruments
import com.gridgain.demo.datagen.observability.LifecycleEvent
import com.gridgain.demo.datagen.observability.OtelInitializer
import com.gridgain.demo.datagen.observability.RunLog
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.runtime.RunId
import com.gridgain.demo.datagen.scenario.KeyRegistry
import com.gridgain.demo.datagen.scenario.ScenarioResult
import com.gridgain.demo.datagen.scenario.ScenarioRunner
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.RunHistoryEntry
import com.gridgain.demo.datagen.state.StatePersister
import com.gridgain.demo.datagen.target.Target
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
        val pendingEvents: MutableList<LifecycleEvent> = mutableListOf(),
    )

    fun defaultLogger(): DataGenLogger =
        Slf4jDataGenLogger(LoggerFactory.getLogger("datagen-cli"))

    fun resolve(parsed: CliArgs, logger: DataGenLogger): Resolution {
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

        val openTelemetry = OtelInitializer.fromSpec(parsedConfig.ops.otel, logger)
        val instruments = Instruments(openTelemetry)

        return Resolution(
            parsedConfig = parsedConfig,
            scenario = scenario,
            targetSpec = targetSpec,
            keyColumnByName = keyColumnByName,
            openTelemetry = openTelemetry,
            instruments = instruments,
        )
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

        try {
            val persister = StatePersister()
            val loadedState: GeneratorState? = persister.load(layout.stateFile)
            if (loadedState != null) {
                logger.lifecycle(
                    "loaded prior state: ${loadedState.sequences.size} sequence cursors, " +
                    "${loadedState.keys.sumOf { it.keys.size }} keys, " +
                    "${loadedState.runHistory.size} prior runs (from ${layout.stateFile})."
                )
            }

            val factory = ValueSourceFactory(
                yamlDataRoot = parsed.dataFile.parent,
                seed = 0L,
                loadedState = loadedState,
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

            val runner = ScenarioRunner(
                scenario = resolution.scenario,
                data = resolution.parsedConfig.data,
                generator = gen,
                target = target,
                keyRegistry = keyRegistry,
                instruments = resolution.instruments,
                targetName = resolution.targetSpec.name,
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
            persister.save(newState, layout.stateFile)

            runLog.emit(LifecycleEvent.StatePersisted(
                stateFile = layout.stateFile,
                sequenceCount = newState.sequences.size,
                keyCount = newState.keys.sumOf { it.keys.size },
                runHistorySize = newState.runHistory.size,
            ))

            logger.lifecycle(
                "scenario '${resolution.scenario.name}' complete: ${result.successCount} successes, " +
                "${result.errorCount} errors, achieved_rate=${result.achievedRate}, " +
                "stop_reason=${result.stopReason}"
            )
            logger.lifecycle("result written to $resultFile")
            logger.lifecycle("state written to ${layout.stateFile}")

            return result
        } finally {
            OtelInitializer.close(resolution.openTelemetry)
        }
    }
}
