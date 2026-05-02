package com.gridgain.demo.datagen.config

import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.DataGenLogger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

interface ConfigMigration {
    val fromVersion: Int
    val toVersion: Int
    val description: String
    fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any>
}

class ConfigMigrationRunner(private val migrations: List<ConfigMigration>) {

    fun ensureCurrentVersion(configFile: File, targetVersion: Int, logger: DataGenLogger): String {
        val yamlText = configFile.readText()
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val rawMap = yaml.load<Any>(yamlText) as? MutableMap<String, Any>
            ?: throw MisconfigurationException(
                "Configuration file '${configFile.name}' is empty or not a valid YAML mapping. " +
                "Provide a yaml document with a top-level 'schema_version' field."
            )

        val fileVersion = (rawMap["schema_version"] as? Number)?.toInt()
            ?: throw MisconfigurationException(
                "Configuration file '${configFile.name}' is missing the required 'schema_version' field. " +
                "Add 'schema_version: $targetVersion' as the first line."
            )

        if (fileVersion == targetVersion) return yamlText

        if (fileVersion > targetVersion) {
            throw MisconfigurationException(
                "Configuration file '${configFile.name}' uses schema_version $fileVersion, " +
                "but this version of the data generator only supports up to schema_version $targetVersion. " +
                "Please upgrade the data generator to a version that supports this config file."
            )
        }

        var current = fileVersion
        var map = rawMap
        while (current < targetVersion) {
            val migration = migrations.find { it.fromVersion == current }
                ?: throw MisconfigurationException(
                    "No migration path from schema_version $current to $targetVersion. " +
                    "Cannot auto-upgrade configuration file '${configFile.name}'. " +
                    "Update the file by hand or downgrade the data generator."
                )
            logger.lifecycle(
                "Migrating ${configFile.name}: schema_version $current -> ${migration.toVersion} (${migration.description})"
            )
            map = migration.migrate(map)
            map["schema_version"] = migration.toVersion
            current = migration.toVersion
        }

        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indicatorIndent = 0
            indent = 2
        }
        val updatedText = Yaml(dumperOptions).dump(map)
        configFile.writeText(updatedText)
        logger.lifecycle("Updated '${configFile.name}' to schema_version $targetVersion.")

        return updatedText
    }
}
