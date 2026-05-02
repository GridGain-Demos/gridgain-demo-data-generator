package com.gridgain.demo.datagen.config

class MigrateV1toV2 : ConfigMigration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2
    override val description: String = "ensure top-level schemas list is present"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        if (!yaml.containsKey("schemas")) {
            yaml["schemas"] = emptyList<Any>()
        }
        return yaml
    }
}
