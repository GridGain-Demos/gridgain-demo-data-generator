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
