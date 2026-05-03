package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import net.datafaker.Faker

data class BusinessEvent(
    val parentSchemaName: String,
    val parentRow: LinkedHashMap<String, Any?>,
    val childrenBySchema: Map<String, List<LinkedHashMap<String, Any?>>>,
)

class BusinessEventGenerator(
    data: DataConfig,
    rootSchemaName: String,
    factory: ValueSourceFactory,
    faker: Faker,
    cohortSeed: Long,
) {

    private val rootSchema: SchemaSpec = data.schemas.firstOrNull { it.name == rootSchemaName }
        ?: throw MisconfigurationException(
            "BusinessEventGenerator: rootSchemaName '$rootSchemaName' is not declared in data.yaml. " +
            "Available schemas: ${data.schemas.joinToString(", ") { it.name }}."
        )

    private val rootGenerator: RowGenerator = RowGenerator(rootSchema, factory, faker)

    /**
     * For each child schema with a parent-fk-ref to the root, store its RowGenerator and the
     * cohort buckets to consult when deciding how many child rows to emit per parent.
     */
    private val childPlans: List<ChildPlan> = data.schemas
        .mapNotNull { childSchema ->
            val fkColumn = childSchema.columns.firstOrNull { col ->
                val vs = col.valueSource
                vs is ParentFkRefSpec && vs.parentSchema == rootSchemaName
            } ?: return@mapNotNull null
            val fkSpec = fkColumn.valueSource as ParentFkRefSpec
            ChildPlan(
                schemaName = childSchema.name,
                generator = RowGenerator(childSchema, factory, faker),
                buckets = fkSpec.cohortBuckets,
            )
        }

    private val sampler: CohortSampler = CohortSampler(seed = cohortSeed)

    fun next(): BusinessEvent {
        val parentRow = rootGenerator.next()
        val children: Map<String, List<LinkedHashMap<String, Any?>>> = childPlans.associate { plan ->
            val counts = sampler.assign(parentCount = 1, buckets = plan.buckets)
            val childCount = counts[0]
            plan.schemaName to (0 until childCount).map { plan.generator.next(parentRow = parentRow) }
        }
        return BusinessEvent(
            parentSchemaName = rootSchema.name,
            parentRow = parentRow,
            childrenBySchema = children,
        )
    }

    private data class ChildPlan(
        val schemaName: String,
        val generator: RowGenerator,
        val buckets: List<CohortBucket>,
    )
}
