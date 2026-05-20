package com.gridgain.demo.datagen.provisioning

/**
 * Pure renderer: ProvisioningPlan -> multi-statement SQL DDL string.
 *
 * Single shared `gg_demo_zone` (extensible per future plan). One CREATE TABLE per descriptor.
 * SqlType.BIGINT -> BIGINT; SqlType.VARCHAR -> VARCHAR(256) (length is a portability default).
 *
 * GG9 requires the affinity column to be part of the primary key for COLOCATE BY — appended
 * to PK when set. Identifier quoting is NOT applied (Plan 9 v1); future plan should add it
 * if data.yaml ever carries reserved-word column names.
 */
class Gg9SqlDdlRenderer {
    fun render(plan: ProvisioningPlan): String = buildString {
        append("CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';\n")
        plan.descriptors.forEachIndexed { i, d ->
            if (i > 0) append('\n')
            append(renderTable(d))
        }
    }.trimEnd('\n')

    private fun renderTable(d: SchemaDescriptor): String = buildString {
        append("CREATE TABLE IF NOT EXISTS ${d.schemaName} (\n")
        d.columns.forEach { col ->
            val sqlType = when (col.type) { SqlType.BIGINT -> "BIGINT"; SqlType.VARCHAR -> "VARCHAR(256)" }
            val nullable = if (col.isKey || col.isAffinity) " NOT NULL" else ""
            append("    ${col.name} $sqlType$nullable,\n")
        }
        val pkCols = buildList {
            add(d.keyColumn)
            if (d.affinityColumn != null && d.affinityColumn != d.keyColumn) add(d.affinityColumn)
        }
        append("    PRIMARY KEY (${pkCols.joinToString(", ")})\n")
        append(")")
        // GG9 syntax requires COLOCATE BY to precede ZONE; reversing them produces
        // "Failed to parse query: Encountered \"COLOCATE\"...".
        if (d.affinityColumn != null) append(" COLOCATE BY (${d.affinityColumn})")
        append(" ZONE gg_demo_zone")
        append(";")
    }
}
