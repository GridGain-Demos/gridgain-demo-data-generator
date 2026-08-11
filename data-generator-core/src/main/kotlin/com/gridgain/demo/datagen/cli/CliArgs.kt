package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.errors.MisconfigurationException
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
     * Optional override for `ops.otel.endpoint`. When present, wins over the ops.yaml
     * value. When `ops.otel.exporter == NONE`, the override implicitly upgrades the
     * exporter to OTLP (the override only makes sense if exporting is wanted). Plugin-
     * driven runs use this flag to inherit the deployed Prometheus/Grafana monitor's
     * OTLP collector endpoint without forcing the user to copy it into ops.yaml
     * (closes F13). Standalone generator runs leave it null.
     */
    val otelEndpointOverride: String? = null,
)

private val REQUIRED_FLAGS = listOf(
    "--data", "--ops", "--scenario", "--cluster-endpoints", "--output", "--run-group",
)

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
                    "; optional: --otel-endpoint-override <url>."
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
        otelEndpointOverride = map["--otel-endpoint-override"]?.takeIf { it.isNotBlank() },
    )
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
                " [--otel-endpoint-override <url>]."
        )
    }
    return value
}
