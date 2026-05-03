package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.*

/**
 * Builds a [ProvisioningPlan] from `(DataConfig, ScenarioSpec)`.
 *
 * Per-column SqlType inference is value-source-driven (data.yaml does not declare types):
 *   SequenceSpec        -> BIGINT
 *   KeySuffixSpec, DataFakerSpec, WeightedChoiceSpec, YamlDataSpec, UniqueSpec -> VARCHAR
 *   ParentFkRefSpec     -> inherited from parent col; falls back to VARCHAR if unresolved.
 *
 * `transactional` is set when scenario.transactionScope == BUSINESS_EVENT, applied to every
 * descriptor. Plan 9 closes follow-up F6 by emitting CacheAtomicityMode.TRANSACTIONAL on GG8
 * when this flag is true.
 */
object ProvisioningPlanFactory {

    fun from(data: DataConfig, scenario: ScenarioSpec): ProvisioningPlan {
        val schemasByName = data.schemas.associateBy { it.name }
        val transactional = scenario.transactionScope == TransactionScope.BUSINESS_EVENT
        val descriptors = data.schemas.map { schema ->
            val keyColumn = schema.columns.firstOrNull { it.key }?.name
                ?: error("schema '${schema.name}' has no key column — KeyColumnValidator should have rejected this earlier")
            val affinityColumn = schema.columns.firstOrNull { it.affinity }?.name
            val cols = schema.columns.map { col ->
                ColumnDescriptor(
                    name = col.name,
                    type = inferType(col.valueSource, schemasByName),
                    isKey = col.key,
                    isAffinity = col.affinity,
                )
            }
            SchemaDescriptor(schema.name, keyColumn, affinityColumn, cols, transactional)
        }
        return ProvisioningPlan(descriptors)
    }

    private fun inferType(vs: ValueSourceSpec, schemasByName: Map<String, SchemaSpec>): SqlType =
        when (vs) {
            is SequenceSpec -> SqlType.BIGINT
            is KeySuffixSpec, is DataFakerSpec, is WeightedChoiceSpec, is YamlDataSpec, is UniqueSpec -> SqlType.VARCHAR
            is ParentFkRefSpec -> {
                val parentCol = schemasByName[vs.parentSchema]?.columns?.firstOrNull { it.name == vs.parentColumn }
                if (parentCol != null) inferType(parentCol.valueSource, schemasByName) else SqlType.VARCHAR
            }
        }
}
