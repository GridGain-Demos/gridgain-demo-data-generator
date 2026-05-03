package com.gridgain.demo.datagen.cli

import java.nio.file.Path
import java.nio.file.Paths

data class CliArgs(
    val dataFile: Path,
    val opsFile: Path,
    val scenarioName: String,
    val clusterEndpoints: Path,
    val outputDir: Path,
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
    )
}
