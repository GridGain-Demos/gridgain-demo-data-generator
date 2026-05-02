package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class CohortBucketSharesValidatorTest {

    private fun col(vs: ValueSourceSpec) = ColumnSpec(name = "fk", nullRate = 0.0, valueSource = vs)

    @Test
    fun `accepts buckets summing to 1`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(SchemaSpec("o", 0.0, listOf(col(
                ParentFkRefSpec("c", "id", listOf(CohortBucket(0.7, 1), CohortBucket(0.3, 5)))
            ))))
        )
        assertThat(CohortBucketSharesValidator().validate(data, OpsConfig(schemaVersion = 1)).errors).isEmpty()
    }

    @Test
    fun `rejects buckets that do not sum to 1`() {
        val data = DataConfig(
            schemaVersion = 2,
            schemas = listOf(SchemaSpec("o", 0.0, listOf(col(
                ParentFkRefSpec("c", "id", listOf(CohortBucket(0.3, 1), CohortBucket(0.3, 5)))
            ))))
        )
        val r = CohortBucketSharesValidator().validate(data, OpsConfig(schemaVersion = 1))
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors[0]).contains("o.fk").contains("0.6")
    }
}
