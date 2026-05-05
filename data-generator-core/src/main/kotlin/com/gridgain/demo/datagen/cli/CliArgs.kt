package com.gridgain.demo.datagen.cli

import java.nio.file.Path
import java.nio.file.Paths

data class CliArgs(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpoints: Path,
    val outputDir: Path,
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

fun parseArgs(args: Array<String>): CliArgs {
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
        otelEndpointOverride = map["--otel-endpoint-override"]?.takeIf { it.isNotBlank() },
    )
}
