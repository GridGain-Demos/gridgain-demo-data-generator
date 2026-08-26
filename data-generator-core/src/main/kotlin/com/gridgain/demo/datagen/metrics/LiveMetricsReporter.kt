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
 * The histogram is encoded on **every** tick, not only on the final `active=false` snapshot. A
 * SIGTERM does now get a bounded graceful stop (`ScenarioRunnerCli.GRACEFUL_STOP_SECONDS`), so an
 * orderly teardown reaches [close] and says goodbye — but a SIGKILL, a grace period that overruns,
 * or a lost node still does not, and per-tick publishing means those runs leave a last-known-good
 * summary behind anyway.
 *
 * The cost is roughly 5-30 KB per instance per second at the recommended bounds, republished each
 * tick because the histogram is cumulative. It grows with the spread of latency buckets touched and
 * the variance in their counts, not with run length, so it plateaus rather than climbing all run.
 * Well inside Kafka's 1 MB default `max.request.size`, which `KafkaMetricsSink` does not override.
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

    init {
        // See MetricsRecorder.isDetached for the full rationale: only provenance distinguishes a
        // detached recorder from a configured one, since both can carry the same bounds.
        require(!recorder.isDetached) {
            "A LiveMetricsReporter was attached to a detached MetricsRecorder, whose histogram bounds " +
                "came from no configuration. Build the recorder from the `metrics:` block of ops.yaml " +
                "instead: MetricsRecorder(LatencyHistogramBounds(spec.histogramHighestMs, " +
                "spec.histogramSignificantDigits)). MetricsRecorder.detached() is only for a recorder " +
                "nobody reads."
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    // Diff baseline — only touched by the reporter thread (and a test, single-threaded).
    private var prev: MetricsRecorder.Counters = recorder.counters()
    private var prevNanos: Long = nanoTime()

    // Run-start baseline, fixed for the life of the reporter. The whole-run rate divides lifetime
    // ops by elapsed time since *this* instant, so it must not move with the diff baseline.
    private val runStartNanos: Long = nanoTime()

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
            runElapsedNanos = now - runStartNanos,
            runLatencyHistogram = HistogramCodec.encode(recorder.histogramSnapshot()),
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
        val now = nanoTime()
        val cur = recorder.counters()
        // prev = cur and intervalNanos = 0L reproduce the same zero interval rate and zero interval
        // latency this used to hand-write, but now through the one shared arithmetic home instead of
        // a second copy of it. targetTps is zeroed because the run is over — reporting the last
        // requested rate would leave a consumer's target line hanging above zero.
        val snapshot = LiveMetrics.computeSnapshot(
            prev = cur, cur = cur, intervalNanos = 0L,
            runElapsedNanos = now - runStartNanos,
            runLatencyHistogram = HistogramCodec.encode(recorder.histogramSnapshot()),
            targetTps = 0.0, runGroup = runGroup, runId = runId,
            nowMs = clockMs(), active = false,
        )
        runCatching { sink.emit(snapshot) }
        runCatching { (sink as? AutoCloseable)?.close() }
    }
}
