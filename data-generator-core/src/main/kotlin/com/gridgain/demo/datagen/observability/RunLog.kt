package com.gridgain.demo.datagen.observability

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import kotlin.text.Charsets.UTF_8

/**
 * Per-run multi-document yaml log under `runs/<run-id>/run.log.yaml` (spec §7).
 * Each emitted [LifecycleEvent] appends one yaml document plus (when an OTel `Logger`
 * is provided) one OTel log record. Pattern mirrors the plugin's `YamlEffectSink`
 * (gridgain-demo-gradle-plugin/.../recording/EffectRecorder.kt): append-mode,
 * `WRITE_DOC_START_MARKER` enabled, parent dir materialised lazily. `otelLogger` is
 * intentionally nullable so callers can choose yaml-only without instantiating a
 * noop logger from `OpenTelemetry.noop().logsBridge.get(...)`.
 */
class RunLog(private val runLogFile: Path, private val otelLogger: Logger?) {

    private val mapper: YAMLMapper = YAMLMapper.builder()
        .enable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build()
        .registerKotlinModule() as YAMLMapper

    init { Files.createDirectories(runLogFile.parent) }

    fun emit(event: LifecycleEvent) {
        val doc = linkedMapOf<String, Any>(
            "event" to event.name(),
            "timestamp" to Instant.now().toString(),
            "attributes" to event.toAttributes(),
        )
        Files.newBufferedWriter(runLogFile, UTF_8, CREATE, APPEND).use { mapper.writeValue(it, doc) }
        otelLogger?.let { logger ->
            val attrs = Attributes.builder().also { b ->
                event.toAttributes().forEach { (k, v) -> b.put(k, v) }
            }.build()
            logger.logRecordBuilder().setSeverity(Severity.INFO)
                .setBody(event.name()).setAllAttributes(attrs).emit()
        }
    }
}
