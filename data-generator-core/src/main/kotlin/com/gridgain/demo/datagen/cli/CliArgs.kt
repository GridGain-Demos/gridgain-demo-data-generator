package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.PartitionStripe
import java.nio.file.Path
import java.nio.file.Paths

data class CliArgs(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpoints: Path,
    val outputDir: Path,
    /**
     * Identity shared by every instance launched together as one logical run (the toolkit passes
     * its own run id). Each process still generates its own `runId`; this is what lets a consumer
     * attribute a fleet's metrics to the run that produced them, and lets a control command address
     * that fleet. Required so the correlation can never be silently absent.
     */
    val runGroup: String,
    /**
     * The cluster this run writes to, resolved against `client-endpoints.yaml`. Supplied per run
     * rather than per file since ops v7: a scenario describes a load shape, and the same shape is
     * run against different clusters. Required — a run with no cluster has nothing to write to, and
     * defaulting it would silently load the wrong grid.
     */
    val targetCluster: String,
    /**
     * Optional override for `ops.otel.endpoint`. When present, wins over the ops.yaml
     * value. When `ops.otel.exporter == NONE`, the override implicitly upgrades the
     * exporter to OTLP (the override only makes sense if exporting is wanted). Plugin-
     * driven runs use this flag to inherit the deployed Prometheus/Grafana monitor's
     * OTLP collector endpoint without forcing the user to copy it into ops.yaml
     * (closes F13). Standalone generator runs leave it null.
     */
    val otelEndpointOverride: String? = null,
    /**
     * The slice of the key space this process owns, from `--instance-index` / `--instance-count`.
     *
     * Null when neither flag was supplied — a single process owning the whole key space, which is
     * today's behaviour and stays exactly unchanged. Non-null makes every `sequence` value source
     * stride by `instance_count * step` and start at `start + instance_index * step`, so N processes
     * launched with indices `0..N-1` emit disjoint keys whose union is what one unstriped process
     * would have emitted.
     *
     * This exists because nothing else supplied it outside Kubernetes. `Coordinator` derives a stripe
     * per pod, but it only activates in-cluster (it needs POD_NAME / POD_NAMESPACE), so every host and
     * local run got null — and N of them sharing one `data.yaml` with a `sequence` source all started
     * at `start` and wrote the same keys concurrently. Measured on real hardware: 32 processes against
     * a 2-node GG8 cluster collapsed to 93 ops/s at 498 ms latency with **zero errors** and idle CPU on
     * both sides, and 50M+ operations left 383 MB of data because they were overwriting each other.
     */
    val instanceStripe: PartitionStripe? = null,
)

private val REQUIRED_FLAGS = listOf(
    "--data", "--ops", "--scenario", "--cluster-endpoints", "--output", "--run-group", "--target-cluster",
)

/** The flag pair that carries [CliArgs.instanceStripe]. Both or neither — see [parseInstanceStripe]. */
internal const val INSTANCE_INDEX_FLAG = "--instance-index"
internal const val INSTANCE_COUNT_FLAG = "--instance-count"

private const val OPTIONAL_FLAGS_SUFFIX =
    "optional: --otel-endpoint-override <url>, $INSTANCE_INDEX_FLAG <i> $INSTANCE_COUNT_FLAG <n> (both or neither)."

fun parseArgs(args: Array<String>): CliArgs {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (i + 1 >= args.size) {
            throw MisconfigurationException(
                "command-line flag '$key' was given without a value. " +
                    "Every flag takes the form '<flag> <value>'. Required flags: " +
                    REQUIRED_FLAGS.joinToString(" ") { "$it <value>" } +
                    "; $OPTIONAL_FLAGS_SUFFIX"
            )
        }
        map[key] = args[i + 1]
        i += 2
    }
    return CliArgs(
        dataFile = Paths.get(required(map, "--data")),
        opsFile = Paths.get(required(map, "--ops")),
        scenarioName = required(map, "--scenario"),
        clusterEndpoints = Paths.get(required(map, "--cluster-endpoints")),
        outputDir = Paths.get(required(map, "--output")),
        runGroup = required(map, "--run-group"),
        targetCluster = required(map, "--target-cluster"),
        otelEndpointOverride = map["--otel-endpoint-override"]?.takeIf { it.isNotBlank() },
        instanceStripe = parseInstanceStripe(map),
    )
}

/**
 * Builds the process's key-space stripe from `--instance-index` / `--instance-count`, or returns
 * null when neither was supplied.
 *
 * **Both or neither.** One without the other is not a stripe: an index with no count has no stride
 * to take, and a count with no index does not say which slice is ours. Either way the process would
 * have to guess, and guessing here is what produces N workers silently writing the same keys — so it
 * is refused.
 *
 * Every range check is made here rather than left to [PartitionStripe]'s own `init`, even though the
 * two agree. `PartitionStripe` fails with `partitionId 4 out of range [0, 2)` — accurate, but it names
 * internal fields the operator never typed, from a `require` that reads as a generator bug rather than
 * as a wrong launch argument. The messages below name the flags.
 */
internal fun parseInstanceStripe(map: Map<String, String>): PartitionStripe? {
    val rawIndex = map[INSTANCE_INDEX_FLAG]?.takeIf { it.isNotBlank() }
    val rawCount = map[INSTANCE_COUNT_FLAG]?.takeIf { it.isNotBlank() }

    if (rawIndex == null && rawCount == null) return null
    if (rawIndex == null || rawCount == null) {
        val supplied = if (rawIndex != null) INSTANCE_INDEX_FLAG else INSTANCE_COUNT_FLAG
        val missing = if (rawIndex != null) INSTANCE_COUNT_FLAG else INSTANCE_INDEX_FLAG
        throw MisconfigurationException(
            "'$supplied' was supplied without '$missing'. $INSTANCE_INDEX_FLAG and $INSTANCE_COUNT_FLAG " +
                "divide the key space between concurrently running generator processes, and they only " +
                "mean anything together: $INSTANCE_COUNT_FLAG is how many processes share the space and " +
                "$INSTANCE_INDEX_FLAG (0-based) is which slice this one owns. Pass both — e.g. " +
                "'$INSTANCE_INDEX_FLAG 0 $INSTANCE_COUNT_FLAG 4' for the first of four — or neither, in " +
                "which case this process owns the whole key space."
        )
    }

    val count = rawCount.toIntOrNull() ?: throw MisconfigurationException(
        "'$INSTANCE_COUNT_FLAG $rawCount' is not an integer. It is how many generator processes are " +
            "sharing the key space, so it must be a whole number of 1 or more."
    )
    val index = rawIndex.toIntOrNull() ?: throw MisconfigurationException(
        "'$INSTANCE_INDEX_FLAG $rawIndex' is not an integer. It is the 0-based slice of the key space " +
            "this process owns, so it must be a whole number in [0, $INSTANCE_COUNT_FLAG)."
    )
    if (count < 1) {
        throw MisconfigurationException(
            "'$INSTANCE_COUNT_FLAG $count' is not a usable process count. It is how many generator " +
                "processes are sharing the key space, so it must be 1 or more. Drop both " +
                "$INSTANCE_INDEX_FLAG and $INSTANCE_COUNT_FLAG for a single process owning the whole " +
                "key space."
        )
    }
    if (index !in 0 until count) {
        throw MisconfigurationException(
            "'$INSTANCE_INDEX_FLAG $index' is out of range for '$INSTANCE_COUNT_FLAG $count'. The index " +
                "is 0-based, so with $count processes the valid values are 0..${count - 1} and each must " +
                "be used exactly once — two processes sharing an index write the same keys, which is the " +
                "collision these flags exist to prevent."
        )
    }
    return PartitionStripe(partitionId = index, partitionCount = count)
}

/** Fails with a message that names the missing flag and the full required set — a bare
 *  `NoSuchElementException` from a map lookup tells the operator nothing actionable. */
private fun required(map: Map<String, String>, flag: String): String {
    val value = map[flag]
    if (value.isNullOrBlank()) {
        val missing = REQUIRED_FLAGS.filter { map[it].isNullOrBlank() }
        throw MisconfigurationException(
            "required command-line flag '$flag' is missing or empty. " +
                "Missing flags: ${missing.joinToString(", ")}. " +
                "Expected invocation: " + REQUIRED_FLAGS.joinToString(" ") { "$it <value>" } +
                " [--otel-endpoint-override <url>] [$INSTANCE_INDEX_FLAG <i> $INSTANCE_COUNT_FLAG <n>]."
        )
    }
    return value
}
