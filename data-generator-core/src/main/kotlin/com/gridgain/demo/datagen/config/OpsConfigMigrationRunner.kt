package com.gridgain.demo.datagen.config

object OpsConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(
        MigrateOpsV1toV2(),
        MigrateOpsV2toV3(),
        MigrateOpsV3toV4(),
        MigrateOpsV4toV5(),
        MigrateOpsV5toV6(),
    ))
}
