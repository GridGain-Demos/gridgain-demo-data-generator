package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.SchemaSpec
import net.datafaker.Faker

class RowGenerator(
    private val schema: SchemaSpec,
    factory: ValueSourceFactory,
    private val faker: Faker,
) {
    private val sources: List<Pair<String, ValueSource>> =
        schema.columns.map { it.name to factory.build(schemaName = schema.name, column = it) }

    fun next(parentRow: Map<String, Any?>? = null): LinkedHashMap<String, Any?> {
        val rowSoFar: MutableMap<String, Any?> = mutableMapOf()
        val ctx = GenerationContext(faker = faker, rowSoFar = rowSoFar, parentRow = parentRow)
        val out = LinkedHashMap<String, Any?>(sources.size)
        for ((name, source) in sources) {
            val value = source.next(ctx)
            rowSoFar[name] = value
            out[name] = value
        }
        return out
    }
}
