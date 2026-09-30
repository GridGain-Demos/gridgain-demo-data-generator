package com.gridgain.demo.datagen.config

object OpsConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(
        MigrateOpsV1toV2(),
        MigrateOpsV2toV3(),
        MigrateOpsV3toV4(),
        MigrateOpsV4toV5(),
        MigrateOpsV5toV6(),
        MigrateOpsV6toV7(),
        MigrateOpsV7toV8(),
        MigrateOpsV8toV9(),
        MigrateOpsV9toV10(),
    ))
}
