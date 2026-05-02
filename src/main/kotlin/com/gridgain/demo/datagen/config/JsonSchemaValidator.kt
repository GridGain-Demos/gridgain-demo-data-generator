package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

object JsonSchemaValidator {

    private val yamlMapper: YAMLMapper = YAMLMapper()
    private val factory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    fun validateData(yamlText: String, fileName: String, version: Int = CURRENT_DATA_SCHEMA_VERSION) {
        validate(
            yamlText = yamlText,
            fileName = fileName,
            schemaResource = "/schema/data/v$version.schema.json",
            kind = "data",
            version = version,
        )
    }

    fun validateOps(yamlText: String, fileName: String, version: Int = CURRENT_OPS_SCHEMA_VERSION) {
        validate(
            yamlText = yamlText,
            fileName = fileName,
            schemaResource = "/schema/ops/v$version.schema.json",
            kind = "ops",
            version = version,
        )
    }

    private fun validate(yamlText: String, fileName: String, schemaResource: String, kind: String, version: Int) {
        val node: JsonNode = try {
            yamlMapper.readTree(yamlText)
        } catch (e: Exception) {
            throw MisconfigurationException(
                "Configuration file '$fileName' is not valid YAML: ${e.message}. " +
                "Verify the file is a UTF-8 yaml document.",
                cause = e
            )
        }

        if (!node.isObject) {
            throw MisconfigurationException(
                "Configuration file '$fileName' must be a yaml mapping (object) at the top level. " +
                "Found ${node.nodeType.name.lowercase()} instead."
            )
        }

        val schema: JsonSchema = loadSchema(schemaResource, kind, version)

        val errors = schema.validate(node)
        if (errors.isNotEmpty()) {
            val details = errors.joinToString(separator = "\n  - ", prefix = "  - ") { it.message }
            throw MisconfigurationException(
                "Configuration file '$fileName' failed JSONSchema validation against $kind v$version:\n$details\n" +
                "Fix each item above and re-run."
            )
        }
    }

    private fun loadSchema(schemaResource: String, kind: String, version: Int): JsonSchema {
        val stream = JsonSchemaValidator::class.java.getResourceAsStream(schemaResource)
            ?: throw MisconfigurationException(
                "Unknown $kind schema version $version. " +
                "This is a data-generator bug — the file '$schemaResource' is missing from the jar."
            )
        return stream.use { factory.getSchema(it) }
    }
}
