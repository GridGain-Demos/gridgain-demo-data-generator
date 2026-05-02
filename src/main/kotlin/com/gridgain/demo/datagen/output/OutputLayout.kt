package com.gridgain.demo.datagen.output

import java.nio.file.Files
import java.nio.file.Path

class OutputLayout(demoOutputDirectory: Path) {
    val generatorRoot: Path = demoOutputDirectory.resolve("data-generator")
    val provisioning: Path = generatorRoot.resolve("provisioning")
    val state: Path = generatorRoot.resolve("state")
    val stateFile: Path = state.resolve("state.yaml")
    private val runs: Path = generatorRoot.resolve("runs")

    fun runDir(runId: String): Path = runs.resolve(runId)
    fun resultFile(runId: String): Path = runDir(runId).resolve("result.yaml")
    fun runLogFile(runId: String): Path = runDir(runId).resolve("run.log.yaml")

    fun ensureBaseDirectories() {
        Files.createDirectories(generatorRoot)
        Files.createDirectories(provisioning)
        Files.createDirectories(state)
    }
}
