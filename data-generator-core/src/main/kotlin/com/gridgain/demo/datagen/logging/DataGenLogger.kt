package com.gridgain.demo.datagen.logging

import org.slf4j.Logger

interface DataGenLogger {
    fun lifecycle(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
    fun debug(message: String)
}

class Slf4jDataGenLogger(private val log: Logger) : DataGenLogger {
    override fun lifecycle(message: String) = log.info(message)
    override fun info(message: String) = log.info(message)
    override fun warn(message: String) = log.warn(message)
    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) log.error(message, throwable) else log.error(message)
    }
    override fun debug(message: String) = log.debug(message)
}
