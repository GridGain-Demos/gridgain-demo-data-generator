package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.config.ParsedConfiguration
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.TargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.runtime.RunId
import com.gridgain.demo.datagen.scenario.ScenarioResult
import com.gridgain.demo.datagen.scenario.ScenarioRunner
import com.gridgain.demo.datagen.target.Target
import net.datafaker.Faker
import org.slf4j.LoggerFactory

object ScenarioRunnerCli {

    data class Resolution(
        val parsedConfig: ParsedConfiguration,
        val scenario: ScenarioSpec,
        val targetSpec: TargetSpec,
        val keyColumnByName: Map<String, String>,
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

        return Resolution(
            parsedConfig = parsedConfig,
            scenario = scenario,
            targetSpec = targetSpec,
            keyColumnByName = keyColumnByName,
        )
    }

    fun run(parsed: CliArgs, resolution: Resolution, target: Target, logger: DataGenLogger): ScenarioResult {
        val factory = ValueSourceFactory(yamlDataRoot = parsed.dataFile.parent, seed = 0L)
        val rootSchema = resolution.scenario.rootSchemas.first()
        val gen = BusinessEventGenerator(
            data = resolution.parsedConfig.data,
            rootSchemaName = rootSchema,
            factory = factory,
            faker = Faker(),
            cohortSeed = 0L,
        )

        val runner = ScenarioRunner(
            scenario = resolution.scenario,
            data = resolution.parsedConfig.data,
            generator = gen,
            target = target,
        )

        val result = runner.run()
        if (target is AutoCloseable) target.close()

        val layout = OutputLayout(parsed.outputDir)
        layout.ensureBaseDirectories()
        val runId = RunId.generate()
        val resultFile = layout.resultFile(runId)
        ScenarioResult.write(result, resultFile)

        logger.lifecycle(
            "scenario '${resolution.scenario.name}' complete: ${result.successCount} successes, " +
            "${result.errorCount} errors, achieved_rate=${result.achievedRate}, " +
            "stop_reason=${result.stopReason}"
        )
        logger.lifecycle("result written to $resultFile")

        return result
    }
}
