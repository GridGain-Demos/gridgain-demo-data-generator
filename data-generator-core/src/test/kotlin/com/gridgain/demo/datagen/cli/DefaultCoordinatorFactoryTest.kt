package com.gridgain.demo.datagen.cli

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.DistributionSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class DefaultCoordinatorFactoryTest {

    private class CapturingLogger : DataGenLogger {
        val warns = mutableListOf<String>()
        override fun lifecycle(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) { warns.add(message) }
        override fun error(message: String, throwable: Throwable?) {}
        override fun debug(message: String) {}
    }

    private fun scenario(distribution: DistributionSpec? = null) = ScenarioSpec(
        name = "load",
        target = "gg8-trip",
        rootSchemas = listOf("customer"),
        rate = ConstantRateSpec(100.0),
        duration = CountDurationSpec(200),
        readRatio = 0.0,
        distribution = distribution,
    )

    @Test
    fun `returns null when the scenario has no distribution block (single-pod mode)`() {
        val coord = ScenarioRunnerCli.DefaultCoordinatorFactory.build(
            scenario(distribution = null),
            env = { name -> if (name == "POD_NAME") "pod-1" else "td-8a-servers" },
            logger = CapturingLogger(),
        )
        assertThat(coord).isNull()
    }

    @Test
    fun `falls back to single-pod and warns when POD_NAME is unset`() {
        val logger = CapturingLogger()
        val coord = ScenarioRunnerCli.DefaultCoordinatorFactory.build(
            scenario(distribution = DistributionSpec(replicas = 3, partitionCount = 12)),
            env = { null },
            logger = logger,
        )
        assertThat(coord).isNull()
        assertThat(logger.warns).hasSize(1)
        assertThat(logger.warns[0])
            .contains("scenario 'load'")
            .contains("POD_NAME")
            .contains("single-pod")
    }

    @Test
    fun `falls back to single-pod and warns when POD_NAMESPACE is unset`() {
        val logger = CapturingLogger()
        val coord = ScenarioRunnerCli.DefaultCoordinatorFactory.build(
            scenario(distribution = DistributionSpec(replicas = 3, partitionCount = 12)),
            env = { name -> if (name == "POD_NAME") "pod-1" else null },
            logger = logger,
        )
        assertThat(coord).isNull()
        assertThat(logger.warns[0]).contains("POD_NAMESPACE")
    }
}
