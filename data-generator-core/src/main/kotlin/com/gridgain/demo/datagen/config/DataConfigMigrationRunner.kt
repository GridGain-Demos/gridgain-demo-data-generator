package com.gridgain.demo.datagen.config

object DataConfigMigrationRunner {
    fun create(): ConfigMigrationRunner = ConfigMigrationRunner(listOf(MigrateV1toV2()))
}
