package com.gridgain.demo.datagen.config

object OpsConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(
        MigrateOpsV1toV2(),
        MigrateOpsV2toV3(),
    ))
}
