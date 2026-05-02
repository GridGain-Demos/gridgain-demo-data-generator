package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.ValueSourceSpec
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import java.nio.file.Path
import java.util.Random

class ValueSourceFactory(
    private val yamlDataRoot: Path,
    private val seed: Long,
    private val uniqueMaxRetries: Int = 1000,
) {

    fun build(column: ColumnSpec): ValueSource {
        val core = buildCore(column.valueSource)
        return if (column.nullRate > 0.0) {
            NullRateApplicator(core, column.nullRate, Random(seed + column.name.hashCode()))
        } else {
            core
        }
    }

    private fun buildCore(spec: ValueSourceSpec): ValueSource = when (spec) {
        is SequenceSpec -> SequenceValueSource(start = spec.start, step = spec.step)
        is DataFakerSpec -> DataFakerValueSource(spec.expression)
        is UniqueSpec -> UniqueValueSource(spec.expression, maxRetries = uniqueMaxRetries)
        is WeightedChoiceSpec -> WeightedChoiceValueSource(spec.choices, seed = seed)
        is YamlDataSpec -> YamlBackedValueSource(
            path = yamlDataRoot.resolve(spec.path),
            key = spec.key,
            random = Random(seed),
        )
    }
}
