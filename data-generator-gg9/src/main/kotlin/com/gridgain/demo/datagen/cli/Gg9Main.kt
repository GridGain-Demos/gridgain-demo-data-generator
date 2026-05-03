@file:JvmName("Gg9Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.Gg9KvTargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.target.Gg9KvTarget
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = ScenarioRunnerCli.defaultLogger()
    try {
        val resolution = ScenarioRunnerCli.resolve(parsed, logger)
        val spec = resolution.targetSpec as? Gg9KvTargetSpec
            ?: throw MisconfigurationException(
                "Gg9Main was launched but the resolved target '${resolution.targetSpec.name}' is " +
                "not a gg9-kv target (kind=${resolution.targetSpec::class.simpleName}). " +
                "The plugin's DataGenerateTask is supposed to dispatch the right flavor — " +
                "if running directly, invoke Gg8Main instead."
            )
        val target = Gg9KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg9) failed: ${e.message}", e)
        exitProcess(1)
    }
}
