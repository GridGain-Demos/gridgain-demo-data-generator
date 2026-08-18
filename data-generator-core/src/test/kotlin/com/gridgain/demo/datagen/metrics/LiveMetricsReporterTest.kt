package com.gridgain.demo.datagen.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveMetricsReporterTest {

    private class CapturingSink : MetricsSink {
        val emitted = mutableListOf<MetricsSnapshot>()
        override fun emit(snapshot: MetricsSnapshot) {
            emitted.add(snapshot)
        }
    }

    @Test
    fun `reports interval throughput and average latency to the sink`() {
        val recorder = MetricsRecorder.detached()
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
        val recorder = MetricsRecorder.detached()
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
        val recorder = MetricsRecorder.detached()
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
}
