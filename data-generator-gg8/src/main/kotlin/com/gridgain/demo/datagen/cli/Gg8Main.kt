@file:JvmName("Gg8Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.target.Gg8KvTarget
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = ScenarioRunnerCli.defaultLogger()
    try {
        val resolution = ScenarioRunnerCli.resolve(parsed, logger)
        val spec = resolution.targetSpec as? Gg8KvTargetSpec
            ?: throw MisconfigurationException(
                "Gg8Main was launched but the resolved target '${resolution.targetSpec.name}' is " +
                "not a gg8-kv target (kind=${resolution.targetSpec::class.simpleName}). " +
                "The plugin's DataGenerateTask is supposed to dispatch the right flavor — " +
                "if running directly, invoke Gg9Main instead."
            )
        val target = Gg8KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg8) failed: ${e.message}", e)
        exitProcess(1)
    }
}
