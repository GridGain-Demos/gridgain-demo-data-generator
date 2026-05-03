package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg9SqlDdlRendererTest {
    private val renderer = Gg9SqlDdlRenderer()

    @Test fun `plain table no affinity`() {
        val plan = ProvisioningPlan(listOf(SchemaDescriptor("customer", "id", null, listOf(
            ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false),
            ColumnDescriptor("name", SqlType.VARCHAR, isKey = false, isAffinity = false),
        ), false)))
        assertThat(renderer.render(plan)).isEqualTo(
            """
            CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';
            CREATE TABLE IF NOT EXISTS customer (
                id BIGINT NOT NULL,
                name VARCHAR(256),
                PRIMARY KEY (id)
            ) ZONE gg_demo_zone;
            """.trimIndent()
        )
    }

    @Test fun `table with COLOCATE BY when affinity column set`() {
        val plan = ProvisioningPlan(listOf(SchemaDescriptor("order", "id", "customer_id", listOf(
            ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
            ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false),
        ), true)))
        assertThat(renderer.render(plan)).isEqualTo(
            """
            CREATE ZONE IF NOT EXISTS gg_demo_zone WITH STORAGE_PROFILES = 'default';
            CREATE TABLE IF NOT EXISTS order (
                customer_id BIGINT NOT NULL,
                id VARCHAR(256) NOT NULL,
                PRIMARY KEY (id, customer_id)
            ) ZONE gg_demo_zone COLOCATE BY (customer_id);
            """.trimIndent()
        )
    }
}
