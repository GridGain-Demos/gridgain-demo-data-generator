@file:JvmName("Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.ConfigurationParser
import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.config.Gg9KvTargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.runtime.RunId
import com.gridgain.demo.datagen.scenario.ScenarioResult
import com.gridgain.demo.datagen.scenario.ScenarioRunner
import com.gridgain.demo.datagen.target.Gg8KvTarget
import com.gridgain.demo.datagen.target.Gg9KvTarget
import com.gridgain.demo.datagen.target.Target
import net.datafaker.Faker
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = Slf4jDataGenLogger(LoggerFactory.getLogger("datagen-cli"))
    try {
        // Set the system property so DemoAddressFinder can locate client-endpoints.yaml.
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

        val target: Target = when (targetSpec) {
            is Gg8KvTargetSpec -> Gg8KvTarget(
                clusterName = targetSpec.clusterName,
                keyColumnByName = keyColumnByName,
                transactionScope = scenario.transactionScope,
            )
            is Gg9KvTargetSpec -> Gg9KvTarget(
                clusterName = targetSpec.clusterName,
                keyColumnByName = keyColumnByName,
                transactionScope = scenario.transactionScope,
            )
        }

        val factory = ValueSourceFactory(yamlDataRoot = parsed.dataFile.parent, seed = 0L)
        val rootSchema = scenario.rootSchemas.first()
        val gen = BusinessEventGenerator(
            data = parsedConfig.data,
            rootSchemaName = rootSchema,
            factory = factory,
            faker = Faker(),
            cohortSeed = 0L,
        )

        val runner = ScenarioRunner(
            scenario = scenario,
            data = parsedConfig.data,
            generator = gen,
            target = target,
        )

        val result = runner.run()
        if (target is AutoCloseable) target.close()

        // Write result.yaml under <output>/data-generator/runs/<run-id>/.
        val layout = OutputLayout(parsed.outputDir)
        val runId = RunId.generate()
        layout.ensureBaseDirectories()
        val resultFile = layout.resultFile(runId)
        ScenarioResult.write(result, resultFile)

        logger.lifecycle(
            "scenario '${scenario.name}' complete: ${result.successCount} successes, " +
            "${result.errorCount} errors, achieved_rate=${result.achievedRate}, " +
            "stop_reason=${result.stopReason}"
        )
        logger.lifecycle("result written to $resultFile")
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator failed: ${e.message}", e)
        exitProcess(1)
    }
}

private data class CliArgs(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpoints: Path,
    val outputDir: Path,
)

private fun parseArgs(args: Array<String>): CliArgs {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(i + 1 < args.size) { "argument $key has no value" }
        map[key] = args[i + 1]
        i += 2
    }
    return CliArgs(
        dataFile = Paths.get(map.getValue("--data")),
        opsFile = Paths.get(map.getValue("--ops")),
        scenarioName = map.getValue("--scenario"),
        clusterEndpoints = Paths.get(map.getValue("--cluster-endpoints")),
        outputDir = Paths.get(map.getValue("--output")),
    )
}
