package com.gridgain.demo.datagen.output

import com.gridgain.demo.datagen.errors.CorruptedStateException
import java.nio.file.Files
import java.nio.file.Path

class OutputLayout(demoOutputDirectory: Path) {
    val generatorRoot: Path = demoOutputDirectory.resolve("data-generator")
    val provisioning: Path = generatorRoot.resolve("provisioning")
    val provisioningGg8: Path = provisioning.resolve("gg8")
    val provisioningGg9: Path = provisioning.resolve("gg9")
    val state: Path = generatorRoot.resolve("state")
    val stateFile: Path = state.resolve("state.yaml")
    private val runs: Path = generatorRoot.resolve("runs")

    fun runDir(runId: String): Path = runs.resolve(runId)
    fun resultFile(runId: String): Path = runDir(runId).resolve("result.yaml")
    fun runLogFile(runId: String): Path = runDir(runId).resolve("run.log.yaml")

    fun ensureBaseDirectories() {
        ensureDir(generatorRoot)
        ensureDir(provisioning)
        ensureDir(provisioningGg8)
        ensureDir(provisioningGg9)
        ensureDir(state)
    }

    /**
     * `Files.createDirectories` throws `FileAlreadyExistsException` when a regular file already
     * sits at the requested path; in older JDK / filesystem combinations it can also succeed
     * silently. Either failure mode produces cryptic downstream errors when later code expects a
     * directory. Detect both and throw `CorruptedStateException` with remediation guidance.
     */
    private fun ensureDir(path: Path) {
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw CorruptedStateException(
                "expected directory at $path but found a regular file. " +
                "Remove or rename the file and re-run, or point demoOutputDirectory at a clean location."
            )
        }
        Files.createDirectories(path)
        if (!Files.isDirectory(path)) {
            throw CorruptedStateException(
                "expected directory at $path but createDirectories did not produce one. " +
                "Inspect the path manually and remove any blocking entries."
            )
        }
    }
}
