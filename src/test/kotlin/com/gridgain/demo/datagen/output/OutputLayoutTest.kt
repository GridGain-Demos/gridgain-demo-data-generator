package com.gridgain.demo.datagen.output

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class OutputLayoutTest {

    @Test
    fun `derives a data-generator subdirectory under the demo output root`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        assertThat(layout.generatorRoot).isEqualTo(root.resolve("data-generator"))
    }

    @Test
    fun `derives provisioning and state directories`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        assertThat(layout.provisioning).isEqualTo(root.resolve("data-generator/provisioning"))
        assertThat(layout.state).isEqualTo(root.resolve("data-generator/state"))
        assertThat(layout.stateFile).isEqualTo(root.resolve("data-generator/state/state.yaml"))
    }

    @Test
    fun `derives a per-run directory from a run id`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        val runDir = layout.runDir("20260502-131415-abc123")
        assertThat(runDir).isEqualTo(root.resolve("data-generator/runs/20260502-131415-abc123"))
        assertThat(layout.resultFile("20260502-131415-abc123"))
            .isEqualTo(runDir.resolve("result.yaml"))
        assertThat(layout.runLogFile("20260502-131415-abc123"))
            .isEqualTo(runDir.resolve("run.log.yaml"))
    }

    @Test
    fun `ensure creates all required directories`(@TempDir root: Path) {
        val layout = OutputLayout(root)
        layout.ensureBaseDirectories()
        assertThat(layout.generatorRoot.toFile()).exists().isDirectory()
        assertThat(layout.provisioning.toFile()).exists().isDirectory()
        assertThat(layout.state.toFile()).exists().isDirectory()
        // runs/<run-id>/ is created on demand, not by ensureBaseDirectories.
    }
}
