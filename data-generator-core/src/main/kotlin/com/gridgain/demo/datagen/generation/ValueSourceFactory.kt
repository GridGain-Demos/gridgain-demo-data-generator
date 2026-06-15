package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.KeySuffixSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.UniqueSpec
import com.gridgain.demo.datagen.config.WeightedChoiceSpec
import com.gridgain.demo.datagen.config.YamlDataSpec
import com.gridgain.demo.datagen.state.GeneratorState
import com.gridgain.demo.datagen.state.SequenceState
import java.nio.file.Path
import java.util.Random

class ValueSourceFactory(
    private val yamlDataRoot: Path,
    private val seed: Long,
    private val uniqueMaxRetries: Int = 1000,
    private val loadedState: GeneratorState? = null,
    /**
     * Non-null in distributed mode; every [SequenceValueSource] this factory builds will
     * stride by `partitionCount * step` and start at `start + partitionId * step`, so two
     * workers never emit the same key. Null in single-pod mode (existing behavior).
     */
    private val partitionStripe: PartitionStripe? = null,
) {

    /** `(schemaName, columnName) → built SequenceValueSource`, populated by `build`. */
    private val sequencesByKey: MutableMap<Pair<String, String>, SequenceValueSource> = mutableMapOf()

    /** Builds a `ValueSource` for `column` in `schemaName`. Sequence sources additionally
     *  consult `loadedState` for a saved cursor and register themselves for `snapshotSequences`. */
    fun build(schemaName: String, column: ColumnSpec): ValueSource {
        val core = buildCore(schemaName, column)
        return if (column.nullRate > 0.0) {
            NullRateApplicator(core, column.nullRate, Random(seed + column.name.hashCode()))
        } else {
            core
        }
    }

    /** Returns one `SequenceState` per `SequenceValueSource` built since this factory was
     *  constructed. Used by `ScenarioRunnerCli` to capture cursors at end of run. */
    fun snapshotSequences(): List<SequenceState> = sequencesByKey.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (k, src) -> SequenceState(k.first, k.second, src.currentNext) }

    private fun buildCore(schemaName: String, column: ColumnSpec): ValueSource = when (val spec = column.valueSource) {
        is SequenceSpec -> {
            val savedNext = loadedState?.sequences
                ?.firstOrNull { it.schemaName == schemaName && it.columnName == column.name }
                ?.nextValue
            val src = SequenceValueSource(
                start = spec.start,
                step = spec.step,
                initialPosition = savedNext,
                partitionStripe = partitionStripe,
            )
            sequencesByKey[schemaName to column.name] = src
            src
        }
        is DataFakerSpec -> DataFakerValueSource(spec.expression)
        is UniqueSpec -> UniqueValueSource(spec.expression, maxRetries = uniqueMaxRetries)
        is WeightedChoiceSpec -> WeightedChoiceValueSource(spec.choices, seed = seed + column.name.hashCode())
        is YamlDataSpec -> YamlBackedValueSource(
            path = yamlDataRoot.resolve(spec.path),
            key = spec.key,
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
