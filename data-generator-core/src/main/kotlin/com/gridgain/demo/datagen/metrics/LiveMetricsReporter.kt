package com.gridgain.demo.datagen.metrics

/**
 * Periodically derives a [MetricsSnapshot] from a [MetricsRecorder] (interval delta → throughput
 * + average execution latency) and hands it to a [MetricsSink]. On [close] it emits a final
 * `active=false` snapshot so live consumers zero out between runs, then closes the sink if it
 * holds resources. Transport-agnostic: the timing/diff loop lives here; where the snapshot goes
 * is the sink's concern.
 *
 * A sink failure on any one tick is swallowed — a dropped metrics point self-heals on the next
 * interval and must never crash the generator run.
 *
 * [targetTps] is a supplier, not a value: the target moves during a run (a `ramped`/`stepped`
 * schedule walks it, and the control channel can override it outright), so it must be sampled at
 * emit time. Reading it once at construction would pin every snapshot to the run's start rate and
 * make a requested-vs-achieved graph wrong.
 */
class LiveMetricsReporter(
    private val recorder: MetricsRecorder,
    private val sink: MetricsSink,
    private val targetTps: () -> Double,
    private val runGroup: String,
    private val runId: String,
    private val intervalMs: Long = 1_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {

    @Volatile private var running = false
    private var thread: Thread? = null

    // Diff baseline — only touched by the reporter thread (and a test, single-threaded).
    private var prev: MetricsRecorder.Counters = recorder.counters()
    private var prevNanos: Long = nanoTime()

    fun start() {
        running = true
        thread = Thread({ loop() }, "datagen-live-metrics").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
            if (running) report(active = true)
        }
    }

    /** Computes a snapshot against the current counters and emits it. Internal so a test can
     *  drive it deterministically without the background thread. */
    internal fun report(active: Boolean) {
        val now = nanoTime()
        val cur = recorder.counters()
        val snapshot = LiveMetrics.computeSnapshot(
            prev = prev, cur = cur, intervalNanos = now - prevNanos,
            targetTps = targetTps(), runGroup = runGroup, runId = runId,
            nowMs = clockMs(), active = active,
        )
        prev = cur
        prevNanos = now
        runCatching { sink.emit(snapshot) }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(2_000)
        val cur = recorder.counters()
        runCatching {
            sink.emit(
                MetricsSnapshot(
                    updatedAtMs = clockMs(),
                    observedTps = 0.0,
                    avgLatencyMs = 0.0,
                    totalOps = cur.ops,
                    errorCount = cur.errors,
                    // The run is over, so there is no target any more — reporting the last
                    // requested rate here would leave a consumer's target line hanging above zero.
                    targetTps = 0.0,
                    runGroup = runGroup,
                    runId = runId,
                    active = false,
                )
            )
        }
        runCatching { (sink as? AutoCloseable)?.close() }
    }
}
