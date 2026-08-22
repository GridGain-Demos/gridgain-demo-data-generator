package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import org.slf4j.LoggerFactory

/**
 * v6 -> v7: `targets:` and every `scenario.target` are removed. A scenario describes a load shape;
 * the cluster it runs against is chosen at launch (`--target-cluster`), so it is no longer written
 * into the file at all.
 *
 * This is the first **lossy** migration: the scenario-to-cluster wiring is real information and
 * nothing in the v7 file records it. So every way a mapping can be discarded is reported at WARN:
 *  - a scenario whose target resolves to a cluster names both, so the operator can reconstruct
 *    their launch arguments directly;
 *  - a scenario whose target does not resolve still warns, naming the scenario and the target name,
 *    without claiming a cause it cannot verify — the name may not have been declared in `targets:`,
 *    the matching entry's `cluster_name` may have been missing or unreadable, or the whole
 *    `targets:` block may have been malformed;
 *  - a scenario whose `target` value is not a string still warns, naming the scenario and the raw
 *    value, instead of being silently dropped by the same expression that removes it;
 *  - a top-level `targets:` block that is present but not a list warns, naming what was found,
 *    before being discarded. Unlike a malformed scenario, which survives into the v7 file and gets
 *    caught by the v7 JSONSchema's `additionalProperties: false`, a malformed `targets:` has no v7
 *    property to be rejected against — this warning is the only chance to report it.
 *
 * Silently dropping any of these would make this a data loss dressed as an upgrade.
 *
 * The logger is injected (not taken from [ConfigMigrationRunner], whose logger is not passed into
 * `migrate`) so the report is assertable. That is dependency injection, not a configuration
 * default, so the comprehensive-configuration-file policy does not apply to it.
 */
class MigrateOpsV6toV7(
    private val logger: DataGenLogger =
        Slf4jDataGenLogger(LoggerFactory.getLogger(MigrateOpsV6toV7::class.java)),
) : ConfigMigration {

    override val fromVersion: Int = 6
    override val toVersion: Int = 7
    override val description: String =
        "remove targets and scenario.target; the target cluster is now chosen at launch"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        val clusterByTargetName = readTargetClusters(yaml)
        yaml.remove("targets")

        @Suppress("UNCHECKED_CAST")
        val scenarios = yaml["scenarios"] as? MutableList<Any> ?: return yaml
        for (element in scenarios) {
            @Suppress("UNCHECKED_CAST")
            val scenario = element as? MutableMap<String, Any> ?: continue
            if (!scenario.containsKey("target")) continue
            val rawTarget = scenario.remove("target")
            val scenarioName = scenario["name"] as? String ?: "(unnamed)"
            val targetName = rawTarget as? String
            if (targetName != null) {
                report(scenarioName, targetName, clusterByTargetName)
            } else {
                reportNonStringTarget(scenarioName, rawTarget)
            }
        }
        return yaml
    }

    /**
     * Resolves each declared target's name to its cluster. If `targets:` is present but is not a
     * list at all (a mapping, a scalar, ...) that is reported here and the block is discarded as
     * empty — this is the only point in the migration where such a malformed block can be seen,
     * since v7 has no `targets` property left to reject it during validation.
     */
    private fun readTargetClusters(yaml: Map<String, Any>): Map<String, String> {
        if (!yaml.containsKey("targets")) return emptyMap()
        val targets = yaml["targets"]
        if (targets !is List<*>) {
            logger.warn(
                "ops migration v6 -> v7 found a 'targets:' block that was not a list: found " +
                    "${describeValue(targets)}. It has been discarded; any scenario below whose " +
                    "target cannot be resolved to a cluster name lost that mapping entirely. " +
                    "Supply '--target-cluster <name>' when running those scenarios."
            )
            return emptyMap()
        }
        return targets.mapNotNull { element ->
            val target = element as? Map<*, *> ?: return@mapNotNull null
            val name = target["name"] as? String ?: return@mapNotNull null
            val cluster = target["cluster_name"] as? String ?: return@mapNotNull null
            name to cluster
        }.toMap()
    }

    private fun report(scenario: String, targetName: String, clusters: Map<String, String>) {
        val cluster = clusters[targetName]
        logger.warn(
            if (cluster != null) {
                "ops migration v6 -> v7 discarded the target of scenario '$scenario': it named " +
                    "target '$targetName' on cluster '$cluster'. The cluster is no longer stored in " +
                    "ops.yaml — pass '--target-cluster $cluster' when running this scenario, or pick " +
                    "the cluster on the demo UI's Load page."
            } else {
                "ops migration v6 -> v7 discarded the target of scenario '$scenario': it named " +
                    "target '$targetName', but no cluster name could be resolved for it from " +
                    "'targets:' (no matching entry was found, the entry's cluster_name was missing " +
                    "or not a string, or the whole 'targets:' block was malformed). Supply the " +
                    "cluster with '--target-cluster <name>' when running this scenario."
            }
        )
    }

    private fun reportNonStringTarget(scenario: String, rawTarget: Any?) {
        logger.warn(
            "ops migration v6 -> v7 discarded the target of scenario '$scenario': its 'target' " +
                "value was ${describeValue(rawTarget)}, not a string, so no cluster name could be " +
                "read from it. Supply the cluster with '--target-cluster <name>' when running this " +
                "scenario."
        )
    }

    /** Names both the shape and, where useful, the content of an unexpected YAML value for a WARN message. */
    private fun describeValue(value: Any?): String = when (value) {
        null -> "an empty value"
        is Map<*, *> -> "a mapping"
        is List<*> -> "a list"
        is String -> "a string ('$value')"
        is Number -> "a number ($value)"
        is Boolean -> "a boolean ($value)"
        else -> "a ${value::class.simpleName}"
    }
}
