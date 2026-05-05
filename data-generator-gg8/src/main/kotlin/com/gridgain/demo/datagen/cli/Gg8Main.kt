@file:JvmName("Gg8Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.Gg8KvTargetSpec
import com.gridgain.demo.datagen.config.ProvisioningMode
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.observability.LifecycleEvent
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.provisioning.Gg8XmlProvisioner
import com.gridgain.demo.datagen.provisioning.ProvisioningOutcome
import com.gridgain.demo.datagen.provisioning.ProvisioningPlanFactory
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

        val mode = resolution.scenario.provisioning
        if (mode != ProvisioningMode.SKIP) {
            val plan = ProvisioningPlanFactory.from(resolution.parsedConfig.data, resolution.scenario)
            val provisioner = Gg8XmlProvisioner(clusterName = spec.clusterName)
            val outcome: ProvisioningOutcome = when (mode) {
                ProvisioningMode.EMIT -> {
                    val layout = OutputLayout(parsed.outputDir).also { it.ensureBaseDirectories() }
                    provisioner.emit(plan, layout.provisioningGg8)
                }
                ProvisioningMode.APPLY -> provisioner.apply(plan)
                ProvisioningMode.SKIP -> error("unreachable")
            }
            if (!outcome.ok) {
                throw MisconfigurationException(
                    "Gg8 provisioning ($mode) failed:\n" +
                    outcome.errors.joinToString(separator = "\n  - ", prefix = "  - ")
                )
            }
            logger.lifecycle("gg8 provisioning ($mode) ok: artifacts=${outcome.artifactsWritten.size} " +
                "created=${outcome.cachesOrTablesCreated.size} existed=${outcome.cachesOrTablesAlreadyExisted.size}")
            resolution.pendingEvents.add(LifecycleEvent.ProvisioningApplied(
                flavor = "gg8",
                mode = mode.name.lowercase(),
                artifactsWritten = outcome.artifactsWritten.size,
                createdCount = outcome.cachesOrTablesCreated.size,
                existedCount = outcome.cachesOrTablesAlreadyExisted.size,
            ))
        }

        val target = Gg8KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg8) failed: ${e.message}", e)
        exitProcess(1)
    }
}
