package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.SchemaSpec
import net.datafaker.Faker

class RowGenerator(
    private val schema: SchemaSpec,
    factory: ValueSourceFactory,
    faker: Faker,
) {
    private val sources: List<Pair<String, ValueSource>> =
        schema.columns.map { it.name to factory.build(it) }
    private val ctx: GenerationContext = GenerationContext(faker)

    fun next(): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(sources.size)
        for ((name, source) in sources) {
            out[name] = source.next(ctx)
        }
        return out
    }
}
