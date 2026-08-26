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

/**
 * A schema with two `parent-fk-ref` columns pointing at the same parent has ambiguous
 * cohort semantics — `BusinessEventGenerator` would silently honor only the first column's
 * cohort buckets. Reject explicitly so users get a remediation message instead of
 * surprising distributions at runtime. Multi-FK to *different* parents is fine.
 */
class MultiFkToSameParentValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            val byParent = schema.columns
                .filter { it.valueSource is ParentFkRefSpec }
                .groupBy { (it.valueSource as ParentFkRefSpec).parentSchema }
            for ((parent, columns) in byParent) {
                if (columns.size > 1) {
                    errors += "schema '${schema.name}' has ${columns.size} parent-fk-ref columns " +
                        "(${columns.joinToString(", ") { it.name }}) pointing at parent '$parent'. " +
                        "BusinessEventGenerator would only honor the first column's cohort buckets — " +
                        "either remove the duplicates or split the relations across distinct parents."
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

class AffinityColumnValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (schema in data.schemas) {
            val affs = schema.columns.filter { it.affinity }
            if (affs.size > 1) {
                errors += "schema '${schema.name}' has more than one affinity column " +
                    "(${affs.joinToString(", ") { it.name }}). " +
                    "Mark exactly one column with 'affinity: true' (or none) — multiple " +
                    "affinity columns have undefined colocation semantics."
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

/**
 * Ties the `external_signal` stop condition to the channel that delivers it.
 *
 * The signal arrives as a `stop` command on the runtime control channel, so a scenario declaring
 * `external_signal` without a top-level `control:` block has nothing that can ever raise it. Paired
 * with `duration: {kind: until_stop_condition}` — which is the pairing the condition exists for —
 * that is an unbounded run with no way to end it short of SIGTERM, and the JSONSchema cannot express
 * the dependency because the two live in different subtrees of the document.
 *
 * Also warns on the converse: `until_stop_condition` with no stop conditions at all. Nothing
 * rejected that before and nothing rejects it now — such a run is bounded only by `ScenarioRunner`'s
 * internal one-minute safety cap, which is a surprise rather than a trap, so it warns rather than
 * failing the parse.
 */
class ExternalSignalControlValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (scenario in ops.scenarios) {
            if (scenario.stopConditions.any { it is ExternalSignalStopSpec } && ops.control == null) {
                errors += "scenario '${scenario.name}' declares the stop condition 'external_signal', " +
                    "but ops.yaml has no top-level 'control:' block — so nothing can ever deliver the " +
                    "signal and the run would be unstoppable short of a SIGTERM. Add the block:\n" +
                    "        control:\n" +
                    "          kafka_bootstrap: \"<broker host:port reachable from the generator>\"\n" +
                    "          topic: \"datagen-control\"\n" +
                    "      or remove the 'external_signal' stop condition from the scenario."
            }
            if (scenario.duration is UntilStopDurationSpec && scenario.stopConditions.isEmpty()) {
                warnings += "scenario '${scenario.name}' has duration kind 'until_stop_condition' but " +
                    "no stop_conditions, so nothing decides when it ends and it will run until the " +
                    "generator's internal safety cap stops it. Add a stop condition — " +
                    "'external_signal' (plus a 'control:' block) for a run an operator ends, or " +
                    "'latency_p99_above'/'error_rate_above' for one the load ends — or use duration " +
                    "kind 'time' or 'count' instead."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = warnings)
    }
}

/**
 * Enforces `partition_count >= replicas` on each scenario's optional `distribution:` block.
 * JSONSchema can't express the cross-field constraint without `$data` refs, so it lives here.
 * `replicas >= 1` and `partition_count >= 1` are already enforced by the JSONSchema.
 */
class DistributionValidator : CrossElementValidator {
    override fun validate(data: DataConfig, ops: OpsConfig): CrossElementValidationResult {
        val errors = mutableListOf<String>()
        for (scenario in ops.scenarios) {
            val dist = scenario.distribution ?: continue
            if (dist.partitionCount < dist.replicas) {
                errors += "scenario '${scenario.name}' has distribution.partition_count=${dist.partitionCount} " +
                    "but distribution.replicas=${dist.replicas}. " +
                    "partition_count must be >= replicas so every worker gets at least one partition. " +
                    "Either lower replicas or raise partition_count."
            }
        }
        return CrossElementValidationResult(errors = errors, warnings = emptyList())
    }
}
