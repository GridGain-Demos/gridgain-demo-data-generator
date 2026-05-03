package com.gridgain.demo.datagen.logging

import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import kotlin.test.Test

class Slf4jDataGenLoggerTest {

    @Test
    fun `lifecycle info warn debug do not throw`() {
        val log = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        log.lifecycle("alpha")
        log.info("beta")
        log.warn("gamma")
        log.debug("delta")
        // No assertion needed: success is "did not throw".
    }

    @Test
    fun `error accepts an optional throwable without throwing`() {
        val log = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        log.error("boom", IllegalStateException("cause"))
        log.error("boom-no-cause")
    }

    @Test
    fun `DataGenLogger is the public interface`() {
        val log: DataGenLogger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))
        assertThat(log).isInstanceOf(DataGenLogger::class.java)
    }
}
