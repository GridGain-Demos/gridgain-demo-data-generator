package com.gridgain.demo.datagen.state

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.config.CURRENT_STATE_SCHEMA_VERSION
import com.gridgain.demo.datagen.errors.CorruptedStateException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Reads and writes `state.yaml`. **No migration support** per spec §6:
 * a `schemaVersion` mismatch is a hard error, mirroring the plugin's
 * `deployment.yaml` policy.
 *
 * Save is atomic: the new contents go to `state.yaml.tmp`, then
 * `Files.move(... ATOMIC_MOVE, REPLACE_EXISTING)` swaps it in. If the
 * filesystem doesn't support atomic move, falls back to a non-atomic
 * `REPLACE_EXISTING` move.
 */
class StatePersister {

    private val mapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    /**
     * Returns `null` when `stateFile` does not exist (first-run case). Any other
     * failure mode — corrupted yaml, mismatched `schemaVersion` — throws
     * `CorruptedStateException` with remediation guidance.
     */
    fun load(stateFile: Path): GeneratorState? {
        if (!Files.exists(stateFile)) return null

        val state: GeneratorState = try {
            mapper.readValue(stateFile.toFile(), GeneratorState::class.java)
        } catch (e: JsonMappingException) {
            throw CorruptedStateException(
                "Failed to parse state file '$stateFile'. " +
                "The data generator's state-file format does not support migration; " +
                "this matches the plugin's `deployment.yaml` rule. " +
                "Remediation: tear down '$stateFile' and rerun from clean state.",
                e,
            )
        } catch (e: Exception) {
            throw CorruptedStateException(
                "Failed to read state file '$stateFile'. " +
                "Remediation: tear down '$stateFile' and rerun from clean state.",
                e,
            )
        }

        if (state.schemaVersion != CURRENT_STATE_SCHEMA_VERSION) {
            throw CorruptedStateException(
                "state.yaml at '$stateFile' has schema_version=${state.schemaVersion}, " +
                "but this generator expects ${CURRENT_STATE_SCHEMA_VERSION}. " +
                "Tear down '$stateFile' and rerun from clean state. The data generator's " +
                "state-file format does not support migration; this matches the plugin's " +
                "`deployment.yaml` rule."
            )
        }

        return state
    }

    /**
     * Atomically writes `state` to `stateFile`. The parent directory is assumed
     * to exist (Plan 9 + F1 left `OutputLayout.ensureBaseDirectories` responsible
     * for that). Falls back to a non-atomic move if the filesystem rejects
     * `ATOMIC_MOVE`.
     */
    fun save(state: GeneratorState, stateFile: Path) {
        val tmp = stateFile.resolveSibling(stateFile.fileName.toString() + ".tmp")
        mapper.writeValue(tmp.toFile(), state)
        try {
            Files.move(
                tmp, stateFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Some filesystems (notably old SMB/NFS, occasionally tmpfs across mounts) reject
            // ATOMIC_MOVE. The fallback is non-atomic but still REPLACE_EXISTING — a partial
            // failure here means the next run sees an absent state.yaml and starts fresh,
            // which is correct first-run behavior.
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
