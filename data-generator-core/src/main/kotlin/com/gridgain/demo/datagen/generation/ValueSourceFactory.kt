package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
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
        val core = buildCore(column)
        return if (column.nullRate > 0.0) {
            NullRateApplicator(core, column.nullRate, Random(seed + column.name.hashCode()))
        } else {
            core
        }
    }

    private fun buildCore(column: ColumnSpec): ValueSource = when (val spec = column.valueSource) {
        is SequenceSpec -> SequenceValueSource(start = spec.start, step = spec.step)
        is DataFakerSpec -> DataFakerValueSource(spec.expression)
        is UniqueSpec -> UniqueValueSource(spec.expression, maxRetries = uniqueMaxRetries)
        is WeightedChoiceSpec -> WeightedChoiceValueSource(spec.choices, seed = seed + column.name.hashCode())
        is YamlDataSpec -> YamlBackedValueSource(
            path = yamlDataRoot.resolve(spec.path),
            key = spec.key,
            // Decorrelate per column — without this, two yaml-backed columns in the same schema
            // (sharing the same seed) draw identical sequences. Mirrors KeySuffixValueSource's
            // pattern. (F3.)
            random = Random(seed + column.name.hashCode()),
        )
        is ParentFkRefSpec -> ParentFkRefValueSource(
            parentSchema = spec.parentSchema,
            parentColumn = spec.parentColumn,
        )
        is KeySuffixSpec -> KeySuffixValueSource(
            baseColumn = spec.baseColumn,
            separator = spec.separator,
            length = spec.length,
            random = Random(seed + spec.baseColumn.hashCode()),
        )
    }
}
