package com.gridgain.demo.datagen.provisioning

import com.gridgain.demo.datagen.config.WriteSyncMode
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg8CacheXmlRendererTest {
    private val renderer = Gg8CacheXmlRenderer()

    /**
     * A replicated cache has to say so in the XML, or the emitted file quietly describes a
     * single-copy cache while data.yaml asks for redundancy — and `emit` exists precisely so
     * someone can apply the file by hand and get what the config said.
     */
    @Test fun `backups and write synchronization are rendered`() {
        val d = SchemaDescriptor(
            "customer", "id", null,
            listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)),
            false, backups = 1, writeSynchronizationMode = WriteSyncMode.FULL_SYNC,
        )

        assertThat(renderer.render(d))
            .contains("""<property name="backups" value="1"/>""")
            .contains("""<property name="writeSynchronizationMode" value="FULL_SYNC"/>""")
    }

    @Test fun `atomic cache no affinity`() {
        val d = SchemaDescriptor("customer", "id", null,
            listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false, 0, WriteSyncMode.PRIMARY_SYNC)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="customer"/>
                    <property name="atomicityMode" value="ATOMIC"/>
                    <property name="backups" value="0"/>
                    <property name="writeSynchronizationMode" value="PRIMARY_SYNC"/>
                </bean>
            </beans>
            """.trimIndent())
    }

    @Test fun `transactional cache with affinity column`() {
        val d = SchemaDescriptor("order", "id", "customer_id",
            listOf(ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                   ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false)),
            true, 0, WriteSyncMode.PRIMARY_SYNC)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="order"/>
                    <property name="atomicityMode" value="TRANSACTIONAL"/>
                    <property name="backups" value="0"/>
                    <property name="writeSynchronizationMode" value="PRIMARY_SYNC"/>
                    <property name="keyConfiguration">
                        <list>
                            <bean class="org.apache.ignite.cache.CacheKeyConfiguration">
                                <constructor-arg index="0" value="java.lang.Object"/>
                                <constructor-arg index="1" value="customer_id"/>
                            </bean>
                        </list>
                    </property>
                </bean>
            </beans>
            """.trimIndent())
    }
}
