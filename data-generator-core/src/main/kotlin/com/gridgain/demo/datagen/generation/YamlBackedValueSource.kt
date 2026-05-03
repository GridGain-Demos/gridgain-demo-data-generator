package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

class YamlBackedValueSource(
    private val path: Path,
    private val key: String,
    private val random: Random,
) : ValueSource {

    private val values: List<Any?> by lazy { loadValues() }

    override fun next(ctx: GenerationContext): Any? = values[random.nextInt(values.size)]

    private fun loadValues(): List<Any?> {
        if (!Files.exists(path)) {
            throw MisconfigurationException(
                "yaml-data value source: file '$path' does not exist. " +
                "Verify the 'path' field on the column references a real yaml file."
            )
        }
        val text = Files.readString(path)
        val map = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Any?>(text) as? Map<String, Any?>
        } catch (e: Exception) {
            throw MisconfigurationException(
                "yaml-data value source: file '$path' is not valid YAML: ${e.message}.",
                cause = e,
            )
        }
            ?: throw MisconfigurationException(
                "yaml-data value source: file '$path' must be a yaml mapping at the top level."
            )

        if (!map.containsKey(key)) {
            throw MisconfigurationException(
                "yaml-data value source: key '$key' not found in '$path'. " +
                "Available keys: ${map.keys.joinToString(", ")}."
            )
        }
        val raw = map[key]
        if (raw !is List<*>) {
            throw MisconfigurationException(
                "yaml-data value source: key '$key' in '$path' must map to a list; " +
                "found ${raw?.javaClass?.simpleName ?: "null"} instead."
            )
        }
        if (raw.isEmpty()) {
            throw MisconfigurationException(
                "yaml-data value source: list under '$key' in '$path' is empty. " +
                "Provide at least one value."
            )
        }
        return raw
    }
}
