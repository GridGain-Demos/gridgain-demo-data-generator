package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.errors.MisconfigurationException

class ParentFkRefValueSource(
    private val parentSchema: String,
    private val parentColumn: String,
) : ValueSource {

    override fun next(ctx: GenerationContext): Any? {
        val parent = ctx.parentRow ?: throw MisconfigurationException(
            "parent-fk-ref to '$parentSchema.$parentColumn' requires a parent row, " +
            "but no parent row was supplied. " +
            "Generate child rows via BusinessEventGenerator, not RowGenerator.next() directly."
        )
        if (!parent.containsKey(parentColumn)) {
            throw MisconfigurationException(
                "parent-fk-ref references '$parentSchema.$parentColumn' " +
                "but the supplied parent row has no such column. " +
                "Verify the parent_column matches a column declared in the '$parentSchema' schema."
            )
        }
        return parent[parentColumn]
    }
}
