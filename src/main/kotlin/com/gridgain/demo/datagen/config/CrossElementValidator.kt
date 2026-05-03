package com.gridgain.demo.datagen.config

data class CrossElementValidationResult(
    val errors: List<String>,
    val warnings: List<String>,
)

interface CrossElementValidator {
    fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult
}

/**
 * v1 default validator. v1 has no fields whose interaction can be checked yet — relations,
 * scenarios, targets, and null_rate are all introduced in Plans 2–4. Adding rules is a
 * matter of writing additional CrossElementValidator implementations and composing them
 * (see CompositeCrossElementValidator).
 */
class DefaultCrossElementValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult =
        CrossElementValidationResult(errors = emptyList(), warnings = emptyList())
}

class CompositeCrossElementValidator(
    private val validators: List<CrossElementValidator>
) : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (v in validators) {
            val r = v.validate(data, ops)
            errors += r.errors
            warnings += r.warnings
        }
        return CrossElementValidationResult(errors, warnings)
    }
}

class ColumnUniquenessValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()

        val seenSchemas = mutableSetOf<String>()
        for (schema in data.schemas) {
            if (!seenSchemas.add(schema.name)) {
                errors += "duplicate schema name '${schema.name}' in data.yaml; " +
                    "schema names must be unique."
            }
            val seenColumns = mutableSetOf<String>()
            for (column in schema.columns) {
                if (!seenColumns.add(column.name)) {
                    errors += "duplicate column name '${column.name}' in schema '${schema.name}'; " +
                        "column names must be unique within a schema."
                }
            }
        }

        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class RelationReferentialValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val schemasByName = data.schemas.associateBy { it.name }
        for (schema in data.schemas) {
            for (column in schema.columns) {
                val vs = column.valueSource
                if (vs is ParentFkRefSpec) {
                    val parent = schemasByName[vs.parentSchema]
                    if (parent == null) {
                        errors += "${schema.name}.${column.name}: parent schema '${vs.parentSchema}' " +
                            "is not declared in data.yaml. Add the schema or correct the parent_schema reference."
                    } else if (parent.columns.none { it.name == vs.parentColumn }) {
                        errors += "${schema.name}.${column.name}: parent column " +
                            "'${vs.parentSchema}.${vs.parentColumn}' is not declared on the parent schema. " +
                            "Verify the parent_column reference."
                    }
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class NullRateOnRelationColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            for (column in schema.columns) {
                if (column.valueSource is ParentFkRefSpec && column.nullRate > 0.0) {
                    errors += "${schema.name}.${column.name}: null_rate is not valid on relation columns; " +
                        "relation columns are populated from their parent and cannot be null."
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class CohortBucketSharesValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            for (column in schema.columns) {
                val vs = column.valueSource
                if (vs is ParentFkRefSpec) {
                    val total = vs.cohortBuckets.sumOf { it.share }
                    if (kotlin.math.abs(total - 1.0) > 0.001) {
                        errors += "${schema.name}.${column.name}: cohort_buckets shares sum to " +
                            "${"%.3f".format(total)} but must sum to 1.0 (within 0.001 tolerance). " +
                            "Adjust the share values so they total 1.0."
                    }
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class ScenarioRootSchemaValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val knownSchemas = data.schemas.map { it.name }.toSet()
        val seenNames = mutableSetOf<String>()
        for (scenario in ops.scenarios) {
            if (!seenNames.add(scenario.name)) {
                errors += "duplicate scenario name '${scenario.name}' in ops.yaml; " +
                    "scenario names must be unique."
            }
            for (root in scenario.rootSchemas) {
                if (root !in knownSchemas) {
                    errors += "scenario '${scenario.name}' references root_schema '$root' " +
                        "which is not declared in data.yaml. " +
                        "Available schemas: ${knownSchemas.joinToString(", ")}."
                }
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}

class KeyColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            val keyColumns = schema.columns.filter { it.key }
            when (keyColumns.size) {
                0 -> errors += "schema '${schema.name}' has no key column. " +
                    "Mark exactly one column with 'key: true'."
                1 -> Unit
                else -> errors += "schema '${schema.name}' has more than one key column " +
                    "(${keyColumns.joinToString(", ") { it.name }}). " +
                    "Mark exactly one column with 'key: true'."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
