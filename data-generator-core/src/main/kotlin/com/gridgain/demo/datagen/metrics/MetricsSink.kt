package com.gridgain.demo.datagen.metrics

/**
 * Where [LiveMetricsReporter] sends each periodic snapshot. Decoupling the transport from the
 * periodic loop keeps the loop unit-testable (an in-memory sink) and lets the generator publish
 * metrics over any channel without touching the timing/diff logic. A sink that holds resources
 * (e.g. a Kafka producer) may also implement [AutoCloseable] — the reporter closes it on stop.
 */
fun interface MetricsSink {
    fun emit(snapshot: MetricsSnapshot)
}
