package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.BoundedKeySpaceSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.KeySelector
import java.util.Random

/**
 * Draws keys from a bounded key space for the run's root schema.
 *
 * Bounded mode **supersedes the key column's own value source**, which is the point of it: a put and
 * a get must be able to name the same row, and a `sequence` key column paired with a bounded read
 * range would guarantee they never do.
 *
 * ### Why only a `sequence` key column is accepted
 *
 * The key space yields an index; something has to turn that into the key the schema uses, and only
 * an arithmetic source makes that well defined and reversible. A DataFaker expression or a
 * `weighted-choice` has no index to invert, so a bound over it could only be honoured by ignoring
 * one half of the pair — a run that looks bounded and is not, where every figure is wrong in a way
 * nothing reports. Refused explicitly instead.
 */
internal class BoundedKeyResolver private constructor(
    private val selector: KeySelector,
    private val start: Long,
    private val step: Long,
) {
    /** A key drawn from the space, in the root schema's own key type. */
    fun nextKey(random: Random): Long = start + selector.next(random) * step

    companion object {
        fun of(
            space: BoundedKeySpaceSpec,
            rootSchemaName: String,
            schemasByName: Map<String, SchemaSpec>,
            keyColumnByName: Map<String, String>,
        ): BoundedKeyResolver {
            val schema = schemasByName[rootSchemaName] ?: throw MisconfigurationException(
                "scenario root schema '$rootSchemaName' is not declared in data.yaml. " +
                    "Add the schema or correct root_schemas."
            )
            val keyColumnName = keyColumnByName.getValue(rootSchemaName)
            val keyColumn = schema.columns.first { it.name == keyColumnName }
            val source = keyColumn.valueSource

            if (source !is SequenceSpec) {
                throw MisconfigurationException(
                    "scenario declares key_space kind 'bounded', but the key column " +
                        "'$rootSchemaName.$keyColumnName' has value_source kind " +
                        "'${source.javaClass.simpleName.removeSuffix("Spec").lowercase()}'. " +
                        "A bounded key space turns an index into a key, which only a 'sequence' " +
                        "source defines — give the key column " +
                        "`value_source: { kind: sequence, start: 1, step: 1 }`, or use " +
                        "`key_space: { kind: unbounded }`."
                )
            }
            if (source.step == 0L) {
                throw MisconfigurationException(
                    "key column '$rootSchemaName.$keyColumnName' has a sequence step of 0, so every " +
                        "key in the bounded key space would be identical. Set a non-zero step."
                )
            }
            return BoundedKeyResolver(
                selector = KeySelector.forDistribution(space.distribution, space.size),
                start = source.start,
                step = source.step,
            )
        }
    }
}
