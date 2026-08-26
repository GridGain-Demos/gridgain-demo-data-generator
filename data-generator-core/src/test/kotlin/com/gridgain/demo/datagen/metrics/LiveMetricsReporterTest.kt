package com.gridgain.demo.datagen.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LiveMetricsReporterTest {

    private class CapturingSink : MetricsSink {
        val emitted = mutableListOf<MetricsSnapshot>()
        override fun emit(snapshot: MetricsSnapshot) {
            emitted.add(snapshot)
        }
    }

    @Test
    fun `reports interval throughput and average latency to the sink`() {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        var nanos = 0L
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { 200.0 },
            runGroup = "grp-1", runId = "run-1",
            clockMs = { 1234L }, nanoTime = { nanos },
        )

        repeat(30) { recorder.record(latencyNanos = 4_000_000L, success = true) } // 30 ops @ 4ms
        nanos = 1_000_000_000L // 1s since the reporter's baseline (nanos=0)
        reporter.report(active = true)

        assertEquals(1, sink.emitted.size)
        val s = sink.emitted.single()
        assertEquals(30.0, s.observedTps, 1e-9)
        assertEquals(4.0, s.avgLatencyMs, 1e-9)
        assertEquals("grp-1", s.runGroup)
        assertEquals("run-1", s.runId)
        assertTrue(s.active)
    }

    @Test
    fun `samples the target rate at emit time so a moving target is reported`() {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        var target = 50.0
        var nanos = 0L
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { target },
            runGroup = "g", runId = "r", clockMs = { 0L }, nanoTime = { nanos },
        )

        nanos = 1_000_000_000L
        reporter.report(active = true)
        target = 400.0 // a ramp step, or a live override from the control channel
        nanos = 2_000_000_000L
        reporter.report(active = true)

        assertEquals(50.0, sink.emitted[0].targetTps, 1e-9)
        assertEquals(400.0, sink.emitted[1].targetTps, 1e-9)
    }

    @Test
    fun `close emits a final inactive snapshot with no target`() {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        val reporter = LiveMetricsReporter(
            recorder, sink, targetTps = { 10.0 }, runGroup = "g", runId = "r", clockMs = { 7L },
        )
        recorder.record(1_000_000L, success = false)

        reporter.close()

        val last = sink.emitted.last()
        assertFalse(last.active)
        assertEquals(0.0, last.observedTps, 1e-9)
        assertEquals(0.0, last.targetTps, 1e-9)
        assertEquals(1L, last.totalOps)
        assertEquals(1L, last.errorCount)
        assertEquals("g", last.runGroup)
    }

    @Test
    fun `whole-run figures accumulate across ticks while interval figures track the tick`() {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        var nanos = 0L
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { 100.0 },
            runGroup = "g", runId = "r", clockMs = { 0L }, nanoTime = { nanos },
        )

        repeat(100) { recorder.record(2_000_000L, success = true) } // 100 ops @ 2ms
        nanos = 1_000_000_000L
        reporter.report(active = true)

        // Second tick: nothing further happens, so the interval rate collapses but the run's
        // average only halves (100 ops over 2s).
        nanos = 2_000_000_000L
        reporter.report(active = true)

        assertEquals(100.0, sink.emitted[0].observedTps, 1e-9)
        assertEquals(100.0, sink.emitted[0].runAvgTps, 1e-9)
        assertEquals(0.0, sink.emitted[1].observedTps, 1e-9)
        assertEquals(50.0, sink.emitted[1].runAvgTps, 1e-9)
        assertEquals(2.0, sink.emitted[1].runAvgLatencyMs, 1e-9)
    }

    @Test
    fun `every tick carries a decodable histogram so a killed run leaves a usable summary`() {
        // A SIGTERM is handled gracefully now, but a SIGKILL (or a grace period that overruns)
        // still never says goodbye. Publishing the histogram on every tick is what makes the
        // last-received tick a usable summary for those runs.
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        var nanos = 0L
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { 0.0 },
            runGroup = "g", runId = "r", clockMs = { 0L }, nanoTime = { nanos },
        )
        repeat(10) { recorder.record(4_000_000L, success = true) }

        nanos = 1_000_000_000L
        reporter.report(active = true)

        val decoded = HistogramCodec.decode(sink.emitted.single().runLatencyHistogram)
        assertEquals(10L, decoded.totalCount)
        assertEquals(4_000.0, decoded.getValueAtPercentile(50.0).toDouble(), 4.0)
    }

    @Test
    fun `the final inactive snapshot carries the whole-run figures and histogram`() {
        val recorder = MetricsRecorder(LatencyHistogramBounds(60_000L, 3))
        val sink = CapturingSink()
        var nanos = 0L
        val reporter = LiveMetricsReporter(
            recorder = recorder, sink = sink, targetTps = { 10.0 },
            runGroup = "g", runId = "r", clockMs = { 7L }, nanoTime = { nanos },
        )
        repeat(50) { recorder.record(1_000_000L, success = false) }

        nanos = 5_000_000_000L
        reporter.close()

        val last = sink.emitted.last()
        assertFalse(last.active)
        assertEquals(0.0, last.observedTps, 1e-9, "the run is over: no interval rate")
        assertEquals(0.0, last.targetTps, 1e-9, "the run is over: no target")
        assertEquals(50L, last.totalOps)
        assertEquals(50L, last.errorCount)
        assertEquals(10.0, last.runAvgTps, 1e-9, "50 ops over 5s")
        assertEquals(1.0, last.runAvgLatencyMs, 1e-9)
        assertEquals(50L, HistogramCodec.decode(last.runLatencyHistogram).totalCount)
    }

    @Test
    fun `attaching a reporter to a detached recorder is refused, naming the metrics block`() {
        val ex = assertThrows<IllegalArgumentException> {
            LiveMetricsReporter(
                recorder = MetricsRecorder.detached(), sink = CapturingSink(), targetTps = { 0.0 },
                runGroup = "g", runId = "r",
            )
        }
        assertTrue(ex.message!!.contains("metrics:"))
    }
}
