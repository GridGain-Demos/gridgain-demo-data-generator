package com.gridgain.demo.datagen.state

import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.errors.CorruptedStateException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class StatePersisterTest {

    private fun sample(version: Int = CURRENT_STATE_SCHEMA_VERSION) = GeneratorState(
        schemaVersion = version,
        sequences = listOf(SequenceState("customer", "id", 17)),
        keys = listOf(KeyRegistryState("customer", KeyType.LONG, listOf("1", "2"))),
        runHistory = listOf(
            RunHistoryEntry(
                runId = "20260504-091215-x9k3pa",
                scenarioName = "customer-load",
                startedAt = "2026-05-04T09:12:15Z",
                completedAt = "2026-05-04T09:12:23Z",
                successCount = 200, errorCount = 0, stopReason = "count reached",
            )
        ),
    )

    @Test fun `load returns null when state file is absent`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        assertThat(StatePersister().load(target)).isNull()
    }

    @Test fun `save then load round-trips`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        val original = sample()
        StatePersister().save(original, target)
        val loaded = StatePersister().load(target)
        assertThat(loaded).isEqualTo(original)
    }

    @Test fun `mismatched schemaVersion throws CorruptedStateException with remediation`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("state.yaml")
        StatePersister().save(sample(version = 999), target)
        assertThatThrownBy { StatePersister().load(target) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining("state-file format does not support migration")
            .hasMessageContaining(target.toString())
            .hasMessageContaining("matches the plugin's `deployment.yaml` rule")
    }

    @Test fun `corrupted yaml throws CorruptedStateException with file path`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        Files.writeString(target, "schema_version: not-an-int\nthis is broken yaml")
        assertThatThrownBy { StatePersister().load(target) }
            .isInstanceOf(CorruptedStateException::class.java)
            .hasMessageContaining(target.toString())
    }

    @Test fun `save uses atomic move via temp file`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        StatePersister().save(sample(), target)
        // Tmp must not linger after a successful save.
        assertThat(Files.exists(dir.resolve("state.yaml.tmp"))).isFalse()
        assertThat(Files.exists(target)).isTrue()
    }

    // In distributed mode only the leader persists state. Followers construct
    // `StatePersister(writable = false)`; any attempt to save throws so the bug
    // surfaces at the offending call site rather than producing torn writes.

    @Test fun `follower-mode persister still loads existing state`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        StatePersister(writable = true).save(sample(), target)
        val loaded = StatePersister(writable = false).load(target)
        assertThat(loaded).isEqualTo(sample())
    }

    @Test fun `follower-mode persister rejects save with remediation`(@TempDir dir: Path) {
        val target = dir.resolve("state.yaml")
        assertThatThrownBy { StatePersister(writable = false).save(sample(), target) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("read-only")
            .hasMessageContaining("leader")
        // No file should have been created.
        assertThat(Files.exists(target)).isFalse()
        assertThat(Files.exists(dir.resolve("state.yaml.tmp"))).isFalse()
    }
}
