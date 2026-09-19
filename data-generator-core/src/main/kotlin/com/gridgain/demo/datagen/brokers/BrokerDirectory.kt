package com.gridgain.demo.datagen.brokers

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.gridgain.demo.datagen.config.AddressBrokerRef
import com.gridgain.demo.datagen.config.BrokerRef
import com.gridgain.demo.datagen.config.ElementBrokerRef
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Where the generator was told to look for `broker-endpoints.yaml`.
 *
 * A sealed pair rather than a nullable [Path]: "no directory was supplied" is a legitimate state a
 * run can be in — the standalone case, and any demo with no `message_brokers` element — and it
 * produces a different, more helpful failure than a path that was supplied and turned out to be
 * wrong. A null would collapse the two.
 */
sealed class BrokerEndpointsSource {
    /** No `--broker-endpoints` was passed. Only [AddressBrokerRef] channels can work. */
    object Absent : BrokerEndpointsSource()

    /** `--broker-endpoints <path>` was passed. The file must exist and parse. */
    data class At(val path: Path) : BrokerEndpointsSource()
}

/**
 * Resolves a [BrokerRef] to a Kafka bootstrap address.
 *
 * The mirror of how a *cluster* is resolved: a scenario names one and `client-endpoints.yaml`
 * supplies the address at launch. The plugin writes both files and is the sole writer of each;
 * this reader duplicates [EXPECTED_SCHEMA_VERSION] rather than depending on the plugin, exactly as
 * `gridgain-demo-client-utils` duplicates the cluster file's version. **Bump both together, or
 * this refuses the file.**
 */
sealed class BrokerDirectory {

    /** Names this directory declares, sorted. Empty when none was supplied. */
    abstract fun declaredNames(): List<String>

    /**
     * The bootstrap address for [ref], or a failure explaining what to do about it.
     *
     * [channel] is `metrics` or `control` — it appears in every message, because an ops.yaml with
     * both blocks would otherwise produce a failure that does not say which one is wrong.
     */
    abstract fun bootstrapServersFor(ref: BrokerRef, channel: String): String

    /** No directory was supplied; only literal addresses can be resolved. */
    private object NotSupplied : BrokerDirectory() {

        override fun declaredNames(): List<String> = emptyList()

        override fun bootstrapServersFor(ref: BrokerRef, channel: String): String = when (ref) {
            is AddressBrokerRef -> ref.bootstrapServers
            is ElementBrokerRef -> throw BrokerResolutionException(
                "ops.yaml '$channel:' names message broker '${ref.name}' (broker.kind: element), but " +
                    "this run was launched without '--broker-endpoints <path>', so there is no " +
                    "directory to resolve that name against.\n\n" +
                    "broker-endpoints.yaml is written by the demo toolkit when it deploys a " +
                    "'message_brokers' element, and every toolkit-launched run passes it " +
                    "automatically. If you are running the generator directly, either pass:\n\n" +
                    "    --broker-endpoints <demoOutputDirectory>/client/$FILE_NAME\n\n" +
                    "or change the block to carry the address instead of the element name:\n\n" +
                    "    $channel:\n" +
                    "      broker:\n" +
                    "        kind: address\n" +
                    "        bootstrap_servers: \"<host:port reachable from the generator>\""
            )
        }
    }

    /** A directory was supplied, read and version-checked. */
    private class Loaded(
        private val byName: Map<String, BrokerEntry>,
        private val path: Path,
    ) : BrokerDirectory() {

        override fun declaredNames(): List<String> = byName.keys.sorted()

        override fun bootstrapServersFor(ref: BrokerRef, channel: String): String = when (ref) {
            is AddressBrokerRef -> ref.bootstrapServers
            is ElementBrokerRef -> resolveElement(ref, channel)
        }

        private fun resolveElement(ref: ElementBrokerRef, channel: String): String {
            val entry = byName[ref.name] ?: throw BrokerResolutionException(
                "ops.yaml '$channel:' names message broker '${ref.name}', but $FILE_NAME at " +
                    "'$path' (schema_version $EXPECTED_SCHEMA_VERSION) declares no broker with " +
                    "that name. It declares: ${declaredNames().ifEmpty { listOf("(none)") }
                        .joinToString(", ")}.\n\n" +
                    "A broker appears in this file only once it has been deployed. Deploy the " +
                    "'${ref.name}' message_brokers element and re-run, or correct the name in " +
                    "ops.yaml."
            )
            if (entry.type != KAFKA_TYPE) throw BrokerResolutionException(
                "ops.yaml '$channel:' names message broker '${ref.name}', which $FILE_NAME " +
                    "declares as type '${entry.type}'. The generator's $channel channel is a " +
                    "Kafka client and cannot talk to it. Name a broker of type '$KAFKA_TYPE', or " +
                    "use 'broker.kind: address' with an address you have verified."
            )
            return entry.bootstrapServers
        }
    }

    // ------------------------------------------------------------------
    // On-disk shape. Private: the contract is the YAML, not these classes.

    private data class BrokerEndpointsFile(
        @JsonProperty("schema_version") val schemaVersion: Int,
        val brokers: List<BrokerEntry> = emptyList(),
    )

    private data class BrokerEntry(
        val name: String,
        @JsonProperty("deployment_kind") val deploymentKind: String,
        val type: String,
        @JsonProperty("bootstrap_servers") val bootstrapServers: String,
    )

    companion object {
        /**
         * The version of `broker-endpoints.yaml` this reader understands.
         *
         * A deliberate duplicate of the plugin's `BrokerEndpointsWriter.SCHEMA_VERSION`. The
         * generator must not depend on the plugin, so the constant is copied and the file refuses
         * anything else by version — the same arrangement `client-endpoints.yaml` uses.
         */
        const val EXPECTED_SCHEMA_VERSION: Int = 1

        private const val FILE_NAME = "broker-endpoints.yaml"
        private const val KAFKA_TYPE = "kafka"

        private val MAPPER: ObjectMapper =
            ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

        /** A directory for a run that was given no endpoints file. */
        fun notSupplied(): BrokerDirectory = NotSupplied

        /**
         * Read and version-check [source].
         *
         * Eager: a supplied path that does not exist means the launcher and the file system
         * disagree, and saying so before the run connects anything is worth more than discovering
         * it at the first lookup.
         */
        fun load(source: BrokerEndpointsSource): BrokerDirectory = when (source) {
            is BrokerEndpointsSource.Absent -> NotSupplied
            is BrokerEndpointsSource.At -> loadFrom(source.path)
        }

        private fun loadFrom(path: Path): BrokerDirectory {
            if (!path.exists()) throw BrokerResolutionException(
                "--broker-endpoints was given '$path', but no such file exists. The demo toolkit " +
                    "writes $FILE_NAME under <demoOutputDirectory>/client/ when it deploys a " +
                    "message broker, and deletes it when the last one is torn down. Deploy a " +
                    "'message_brokers' element, correct the path, or drop the flag and use " +
                    "'broker.kind: address' blocks."
            )
            val parsed = try {
                MAPPER.readValue(path.readText(), BrokerEndpointsFile::class.java)
            } catch (e: Exception) {
                throw BrokerResolutionException(
                    "$FILE_NAME at '$path' is not valid YAML or has an unexpected structure: " +
                        "${e.message}. It is generated, so the repair is to delete it and re-run " +
                        "deployMessageBroker for each deployed broker rather than to hand-edit it."
                )
            }
            if (parsed.schemaVersion != EXPECTED_SCHEMA_VERSION) throw BrokerResolutionException(
                "$FILE_NAME at '$path' has schema_version=${parsed.schemaVersion}, but this " +
                    "generator only understands schema_version=$EXPECTED_SCHEMA_VERSION. The " +
                    "toolkit that wrote it and this generator archive are different versions — " +
                    "rebuild and redeploy the generator archive, or use the toolkit that matches it."
            )
            return Loaded(parsed.brokers.associateBy { it.name }, path)
        }
    }
}

/**
 * A broker reference could not be resolved to an address.
 *
 * Its own type rather than a bare [IllegalStateException] so the CLI can report it as a
 * configuration problem — something the operator fixes in ops.yaml or by deploying an element —
 * rather than as an internal fault.
 */
class BrokerResolutionException(message: String) : RuntimeException(message)
