package com.gridgain.demo.datagen.provisioning

import java.nio.file.Path

/** artifactsWritten: emit() output. cachesOrTablesCreated/AlreadyExisted: apply() outputs.
 *  errors: non-empty means the run failed (rich messages, not codes). */
data class ProvisioningOutcome(
    val artifactsWritten: List<Path>,
    val cachesOrTablesCreated: List<String>,
    val cachesOrTablesAlreadyExisted: List<String>,
    val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}
