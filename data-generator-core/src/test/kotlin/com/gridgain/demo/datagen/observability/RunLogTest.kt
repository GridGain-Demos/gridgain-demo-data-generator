package com.gridgain.demo.datagen.observability

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class RunLogTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test fun `emit writes one yaml document per event`(@TempDir dir: Path) {
        val file = dir.resolve("run.log.yaml")
        val log = RunLog(file, otelLogger = null)
        log.emit(LifecycleEvent.ScenarioStarted("customer-load", "gg8"))
        log.emit(LifecycleEvent.ScenarioStopped("customer-load", "count reached", 200, 0))
        log.emit(LifecycleEvent.StatePersisted(dir.resolve("state.yaml"), 1, 10, 1))

        // Three YAML documents = three `---` markers (Jackson's WRITE_DOC_START_MARKER).
        assertThat(Files.readString(file).lines().filter { it == "---" }).hasSize(3)
        val docs = mapper.readValues(
            mapper.factory.createParser(file.toFile()), Map::class.java,
        ).readAll().filterIsInstance<Map<String, Any>>()
        assertThat(docs.map { it["event"] })
            .containsExactly("scenario.started", "scenario.stopped", "state.persisted")
        assertThat(docs[0]["timestamp"]).isInstanceOf(String::class.java)
        @Suppress("UNCHECKED_CAST")
        assertThat(docs[0]["attributes"] as Map<String, Any>)
            .containsEntry("scenario", "customer-load").containsEntry("target", "gg8")
    }

    @Test fun `emit routes through OTel logger when provided`(@TempDir dir: Path) {
        val exporter = InMemoryLogRecordExporter.create()
        val provider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build()
        val otel = OpenTelemetrySdk.builder().setLoggerProvider(provider).build()
        RunLog(dir.resolve("run.log.yaml"), otel.logsBridge.get(Instruments.SCOPE))
            .emit(LifecycleEvent.ProvisioningApplied("gg8", "apply", 0, 2, 1))
        assertThat(exporter.finishedLogRecordItems).hasSize(1)
        assertThat(exporter.finishedLogRecordItems.first().bodyValue?.value)
            .isEqualTo("provisioning.applied")
        otel.close()
    }
}
