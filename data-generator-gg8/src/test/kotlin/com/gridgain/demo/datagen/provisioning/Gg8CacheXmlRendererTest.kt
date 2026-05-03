package com.gridgain.demo.datagen.provisioning

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class Gg8CacheXmlRendererTest {
    private val renderer = Gg8CacheXmlRenderer()

    @Test fun `atomic cache no affinity`() {
        val d = SchemaDescriptor("customer", "id", null,
            listOf(ColumnDescriptor("id", SqlType.BIGINT, isKey = true, isAffinity = false)), false)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="customer"/>
                    <property name="atomicityMode" value="ATOMIC"/>
                </bean>
            </beans>
            """.trimIndent())
    }

    @Test fun `transactional cache with affinity column`() {
        val d = SchemaDescriptor("order", "id", "customer_id",
            listOf(ColumnDescriptor("customer_id", SqlType.BIGINT, isKey = false, isAffinity = true),
                   ColumnDescriptor("id", SqlType.VARCHAR, isKey = true, isAffinity = false)),
            true)
        assertThat(renderer.render(d)).isEqualTo("""
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="order"/>
                    <property name="atomicityMode" value="TRANSACTIONAL"/>
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
