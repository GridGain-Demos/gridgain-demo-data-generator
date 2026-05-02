package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.logging.DataGenLogger
import java.io.File

data class ParsedConfiguration(val data: DataConfig, val ops: OpsConfig)

class ConfigurationParser(
    private val logger: DataGenLogger,
    private val dataMigrationRunner: ConfigMigrationRunner = DataConfigMigrationRunner.create(),
    private val opsMigrationRunner: ConfigMigrationRunner = ConfigMigrationRunner(emptyList()),
    private val crossElementValidator: CrossElementValidator = CompositeCrossElementValidator(
        listOf(
            DefaultCrossElementValidator(),
            ColumnUniquenessValidator(),
            RelationReferentialValidator(),
            NullRateOnRelationColumnValidator(),
            CohortBucketSharesValidator(),
        )
    ),
) {

    private val yamlMapper: YAMLMapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    fun parse(dataFile: File, opsFile: File): ParsedConfiguration {
        requireExists(dataFile)
        requireExists(opsFile)

        val dataYaml = dataMigrationRunner.ensureCurrentVersion(dataFile, CURRENT_DATA_SCHEMA_VERSION, logger)
        val opsYaml = opsMigrationRunner.ensureCurrentVersion(opsFile, CURRENT_OPS_SCHEMA_VERSION, logger)

        JsonSchemaValidator.validateData(dataYaml, fileName = dataFile.name)
        JsonSchemaValidator.validateOps(opsYaml, fileName = opsFile.name)

        val data: DataConfig = yamlMapper.readValue(dataYaml, DataConfig::class.java)
        val ops: OpsConfig = yamlMapper.readValue(opsYaml, OpsConfig::class.java)

        val crossResult = crossElementValidator.validate(data, ops)
        crossResult.warnings.forEach { logger.warn("[config] $it") }
        if (crossResult.errors.isNotEmpty()) {
            val details = crossResult.errors.joinToString(separator = "\n  - ", prefix = "  - ")
            throw MisconfigurationException(
                "Configuration failed cross-element validation:\n$details\n" +
                "Each item above identifies a configuration value to fix; re-run after correcting them."
            )
        }

        return ParsedConfiguration(data = data, ops = ops)
    }

    private fun requireExists(file: File) {
        if (!file.exists()) {
            throw MisconfigurationException(
                "Configuration file '${file.name}' does not exist at ${file.absolutePath}. " +
                "Provide a valid path or generate one from a template."
            )
        }
    }
}
