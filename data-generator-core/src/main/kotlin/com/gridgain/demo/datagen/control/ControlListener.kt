package com.gridgain.demo.datagen.control

/**
 * Ingress for [ControlCommand]s addressed to this instance. The transport is deliberately behind a
 * seam — the generator binds no server socket, so control arrives over whatever bus the deployment
 * already has (Kafka today, see [KafkaControlListener]), and tests can drive the run loop without a
 * broker.
 */
interface ControlListener : AutoCloseable {
    /** Begin consuming commands. Must not block the caller. */
    fun start()
}
