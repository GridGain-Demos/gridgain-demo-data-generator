@file:JvmName("Gg9Main")
package com.gridgain.demo.datagen.cli

import com.gridgain.demo.client.gg9.DemoAddressFinder
import com.gridgain.demo.datagen.config.Gg9KvTargetSpec
import com.gridgain.demo.datagen.config.ProvisioningMode
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.observability.LifecycleEvent
import com.gridgain.demo.datagen.output.OutputLayout
import com.gridgain.demo.datagen.provisioning.Gg9SqlProvisioner
import com.gridgain.demo.datagen.provisioning.ProvisioningOutcome
import com.gridgain.demo.datagen.provisioning.ProvisioningPlanFactory
import com.gridgain.demo.datagen.target.Gg9KvTarget
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val logger = ScenarioRunnerCli.defaultLogger()
    try {
        val resolution = ScenarioRunnerCli.resolve(parsed, logger)
        // The only variant this entry point can serve — see TargetSpec's KDoc for why the sealed
        // hierarchy is kept even though nothing here takes a TargetSpec parameter.
        val spec = Gg9KvTargetSpec(resolution.targetClusterName)
        resolveTargetClusterOrThrow(spec)

        val mode = resolution.scenario.provisioning
        if (mode != ProvisioningMode.SKIP) {
            val plan = ProvisioningPlanFactory.from(resolution.parsedConfig.data, resolution.scenario)
            val provisioner = Gg9SqlProvisioner(clusterName = spec.clusterName)
            val outcome: ProvisioningOutcome = when (mode) {
                ProvisioningMode.EMIT -> {
                    val layout = OutputLayout(parsed.outputDir).also { it.ensureBaseDirectories() }
                    provisioner.emit(plan, layout.provisioningGg9)
                }
                ProvisioningMode.APPLY -> provisioner.apply(plan)
                ProvisioningMode.SKIP -> error("unreachable")
            }
            if (!outcome.ok) {
                throw MisconfigurationException(
                    "Gg9 provisioning ($mode) failed:\n" +
                    outcome.errors.joinToString(separator = "\n  - ", prefix = "  - ")
                )
            }
            logger.lifecycle("gg9 provisioning ($mode) ok: artifacts=${outcome.artifactsWritten.size} " +
                "created=${outcome.cachesOrTablesCreated.size} existed=${outcome.cachesOrTablesAlreadyExisted.size}")
            resolution.pendingEvents.add(LifecycleEvent.ProvisioningApplied(
                flavor = "gg9",
                mode = mode.name.lowercase(),
                artifactsWritten = outcome.artifactsWritten.size,
                createdCount = outcome.cachesOrTablesCreated.size,
                existedCount = outcome.cachesOrTablesAlreadyExisted.size,
            ))
        }

        val target = Gg9KvTarget(spec.clusterName, resolution.keyColumnByName, resolution.scenario.transactionScope)
        ScenarioRunnerCli.run(parsed, resolution, target, logger)
        exitProcess(0)
    } catch (e: Exception) {
        logger.error("data generator (gg9) failed: ${e.message}", e)
        exitProcess(1)
    }
}

/**
 * Resolves the cluster before generating anything. Without this, an unknown or wrong-major
 * cluster surfaces per-operation inside the target's write path, where the error is counted
 * and discarded — the run reports 0 successes and every op failed, and still exits 0. This is
 * the guard the pre-v7 cast-and-reject used to provide.
 */
internal fun resolveTargetClusterOrThrow(spec: Gg9KvTargetSpec) {
    try {
        DemoAddressFinder(spec.clusterName).addresses
    } catch (e: RuntimeException) {
        throw MisconfigurationException(
            "--target-cluster could not be resolved for a GridGain 9 run: ${e.message}",
            cause = e,
        )
    }
}
