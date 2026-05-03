package com.gridgain.demo.datagen.provisioning

import java.nio.file.Path

/** Plain interface (impls live in different gradle modules). Both methods MUST be idempotent. */
interface Provisioner {
    fun emit(plan: ProvisioningPlan, destinationDir: Path): ProvisioningOutcome
    fun apply(plan: ProvisioningPlan): ProvisioningOutcome
}
