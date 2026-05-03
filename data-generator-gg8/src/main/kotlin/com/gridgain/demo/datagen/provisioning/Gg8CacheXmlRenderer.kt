package com.gridgain.demo.datagen.provisioning

/**
 * Pure renderer: one [SchemaDescriptor] -> one Spring-bean CacheConfiguration XML.
 *
 * - `atomicityMode` is ATOMIC by default; TRANSACTIONAL when descriptor.transactional is true.
 * - When affinityColumn is non-null, a `<property name="keyConfiguration">` block carries a single
 *   CacheKeyConfiguration whose constructor args are ("java.lang.Object", <affinityColumn>).
 *   Matches the runtime path in Gg8XmlProvisioner.apply (Task 8).
 *
 * No XML library — string assembly is sufficient for the deterministic output the golden tests lock.
 */
class Gg8CacheXmlRenderer {
    fun render(d: SchemaDescriptor): String {
        val mode = if (d.transactional) "TRANSACTIONAL" else "ATOMIC"
        // Lines must start at 20-space leading indent so that trimIndent on the
        // outer heredoc (whose natural minimum is 12) strips uniformly across both
        // halves and leaves these property lines at the expected 8-space indent.
        val affinityBlock = d.affinityColumn?.let { col ->
            "\n" + """
                |                    <property name="keyConfiguration">
                |                        <list>
                |                            <bean class="org.apache.ignite.cache.CacheKeyConfiguration">
                |                                <constructor-arg index="0" value="java.lang.Object"/>
                |                                <constructor-arg index="1" value="$col"/>
                |                            </bean>
                |                        </list>
                |                    </property>
            """.trimMargin().trimEnd()
        } ?: ""
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean class="org.apache.ignite.configuration.CacheConfiguration">
                    <property name="name" value="${d.schemaName}"/>
                    <property name="atomicityMode" value="$mode"/>$affinityBlock
                </bean>
            </beans>
        """.trimIndent()
    }
}
