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
 * nothing in the v7 file records it. So each discarded mapping is reported at WARN, naming the
 * scenario, the cluster it used to name, and the flag that now supplies it — an operator who reads
 * the log can reconstruct their launch arguments. Silently dropping it would make this a data loss
 * dressed as an upgrade.
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
            val targetName = scenario.remove("target") as? String ?: continue
            report(scenario["name"] as? String ?: "(unnamed)", targetName, clusterByTargetName)
        }
        return yaml
    }

    private fun readTargetClusters(yaml: Map<String, Any>): Map<String, String> {
        val targets = yaml["targets"] as? List<*> ?: return emptyMap()
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
                    "target '$targetName', which was not declared in targets[], so no cluster name " +
                    "could be recovered from it. Supply the cluster with '--target-cluster <name>' " +
                    "when running this scenario."
            }
        )
    }
}
