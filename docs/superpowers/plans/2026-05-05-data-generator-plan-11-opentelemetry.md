# Data Generator — Plan 11: OpenTelemetry

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec §7. Add OpenTelemetry as the single metrics pipe through a top-level `otel: { exporter: none|otlp|prometheus, endpoint, attributes }` block in `ops.yaml`. Wire the spec's minimum viable instrument set — one histogram (`data_generator.op.latency`), two counters (`data_generator.op.count`, `data_generator.op.errors`), three gauges (`data_generator.in_flight`, `data_generator.target_rate`, `data_generator.observed_rate`) — into `ScenarioRunner.tick()`. Emit lifecycle events (`scenario.started`, `scenario.stopped`, `provisioning.applied`, `state.persisted`) as both OTel logs **and** docs in a per-run multi-doc yaml `runs/<run-id>/run.log.yaml`, mirroring the plugin's `YamlEffectSink` pattern (`gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/recording/EffectRecorder.kt`).

**Architecture:** All OTel code lives in `data-generator-core` — flavor-agnostic. A new `observability/` package owns four classes: `OtelInitializer` (builds an `OpenTelemetry` from the parsed `OtelSpec`), `Instruments` (single registration point — names live nowhere else), `LifecycleEvent` (sealed hierarchy), and `RunLog` (multi-doc yaml writer + OTel `Logger` emitter). `ScenarioRunnerCli.resolve` builds both the `OpenTelemetry` instance and the `Instruments` against it, stashing them on `Resolution` along with a `pendingEvents: MutableList<LifecycleEvent>` buffer. `ScenarioRunnerCli.run` constructs the per-run `RunLog`, drains `pendingEvents` into it, threads `Instruments` into `ScenarioRunner`'s constructor, emits scenario+state lifecycle events, and closes the SDK on the way out. `Gg8Main` / `Gg9Main` append `provisioning.applied` events into `pendingEvents` after their `Provisioner` finishes, so the provisioning hook stays per-flavor without bleeding `OpenTelemetry` into `Provisioner` interfaces. **No GlobalOpenTelemetry registration anywhere** — every consumer takes an `OpenTelemetry` instance explicitly so tests can inject `InMemoryMetricReader` and `InMemoryLogRecordExporter`.

**Tech Stack:** OpenTelemetry Java SDK pinned via `io.opentelemetry:opentelemetry-bom:1.42.0` (`platform(...)`). Modules: `opentelemetry-api`, `opentelemetry-sdk`, `opentelemetry-sdk-metrics`, `opentelemetry-sdk-logs`, `opentelemetry-exporter-otlp`, `opentelemetry-exporter-prometheus` (still incubating; pulled via `:exporters:prometheus` BOM coordinate). Test-side: `opentelemetry-sdk-testing` for `InMemoryMetricReader` + `InMemoryLogRecordExporter`. Reuses the Jackson YAML mapper already wired in core for `run.log.yaml` (no SnakeYAML version override; project pin at `1.33` honored).

---

## Pre-execution prerequisites

1. Post-Plan-10 `main` (three subprojects, **190 tests pass**: 182 unit + 8 env-gated integration) per ROADMAP `Last updated: 2026-05-04`.
2. `gridgain-demo-client-utils` published to maven local.
3. `OutputLayout.runLogFile(runId)` already exists (added pre-Plan-10) and resolves to `<demoOutputDirectory>/data-generator/runs/<run-id>/run.log.yaml` — the spec §7 path. Plan 11 is its first writer.
4. `ScenarioRunnerCli.run` already loads + saves `state.yaml` (Plan 10). Plan 11 inserts metric/log emission around the existing call sites without restructuring them.
5. `ScenarioRunner` already accepts an injected `keyRegistry` (Plan 10 Task 7). Plan 11 adds an `instruments` constructor parameter with a noop default the same way.
6. Plugin endpoint inheritance from a Prometheus/Grafana monitor (mentioned in spec §7) is **out of scope**; Task 11 records the deferral as F13.

---

## Spec extensions (additive)

```yaml
# ops.yaml — top-level `otel:` block, optional; default is `{exporter: none}`.
schema_version: 2
otel:
  exporter: otlp           # none (default) | otlp | prometheus
  endpoint: "http://otel-collector.observability.svc:4318"
  attributes:
    deployment.environment: dev
    service.namespace: gridgain-demo
scenarios:
  - name: customer-load
    target: gg8-cluster
    # ...
```

**No `schema_version` bump.** Field is optional; default `OtelSpec(exporter = "none")` if `otel` omitted, mirroring Plan 9's additive-enum shape (`provisioning`) and Plan 6's `transaction_scope`. **No `data.yaml` changes.** **No `state.yaml` changes** (state schema stays at `1`).

**Default contract.** When `exporter: none` (or the entire block is omitted), `OtelInitializer.fromSpec` returns `OpenTelemetry.noop()`. Every instrument call site is a no-op; `RunLog` still writes the yaml file (the yaml log is a hard requirement of spec §7 independent of the OTel SDK).

---

## Target architecture

```
data-generator-core/
├── build.gradle.kts                                                         # ADD OTel BOM + 6 modules
└── src/main/kotlin/com/gridgain/demo/datagen/
    ├── config/
    │   ├── OpsConfig.kt                                                     # ADD `otel: OtelSpec = OtelSpec.NONE`
    │   └── OtelSpec.kt                                                      # NEW
    ├── observability/                                                       # NEW package
    │   ├── Instruments.kt                                                   # NEW: single registration point
    │   ├── OtelInitializer.kt                                               # NEW: noop / otlp / prometheus
    │   ├── RunLog.kt                                                        # NEW: yaml + OTel Logger sink
    │   └── LifecycleEvent.kt                                                # NEW: sealed hierarchy
    ├── scenario/
    │   └── ScenarioRunner.kt                                                # MODIFY: thread Instruments through tick()
    └── cli/
        └── ScenarioRunnerCli.kt                                             # MODIFY: build Otel + RunLog; emit events

data-generator-gg{8,9}/.../cli/Gg{8,9}Main.kt                                # MODIFY: append ProvisioningApplied (Task 9)
└── src/main/resources/schema/ops/v2.schema.json                             # ADD `otel` defs
└── src/test/kotlin/com/gridgain/demo/datagen/observability/                 # NEW test package
```

**Key types (in prose):**

- `OtelSpec(exporter: OtelExporter, endpoint: String?, attributes: Map<String,String>)` with companion `NONE` for the default singleton. `OtelExporter` is a Jackson-tagged enum: `NONE`, `OTLP`, `PROMETHEUS`.
- `Instruments(otel: OpenTelemetry)` — eager-builds every histogram / counter / gauge from one `Meter`. Public properties `opLatency: DoubleHistogram`, `opCount: LongCounter`, `opErrors: LongCounter`, `inFlight: LongUpDownCounter`, `targetRate: ObservableDoubleGauge`, `observedRate: ObservableDoubleGauge`. The two gauges read from `AtomicReference<Double>` slots also exposed on `Instruments` (`targetRateRef`, `observedRateRef`) so call sites just `set(...)` the AtomicReference; the SDK polls the gauge on its own schedule.
- `OtelInitializer` — `fromSpec(spec): OpenTelemetry` returns either `OpenTelemetry.noop()` or a built `OpenTelemetrySdk`; `close(otel)` quiets the SDK on scope exit.
- `LifecycleEvent` (sealed) — `ScenarioStarted`, `ScenarioStopped(reason, successCount, errorCount)`, `ProvisioningApplied(flavor, mode, artifactsWritten, createdCount, existedCount)`, `StatePersisted(stateFile, sequenceCount, keyCount, runHistorySize)`. Each implements `name(): String` (the `event:` value in yaml + the OTel log body) and `toAttributes(): Map<String, String>` (the yaml `attributes:` doc field + the OTel log attributes).
- `RunLog(runLogFile: Path, otelLogger: Logger?)` — `emit(event: LifecycleEvent)` appends one yaml document to `runLogFile` and (if `otelLogger != null`) emits one OTel log record. Constructor materializes the run dir.

---

## Tasks

### Task 1: Add OTel dependencies to `data-generator-core`

**Files:**
- Modify: `data-generator-core/build.gradle.kts`

**Decision.** All six modules go on the `api(...)` configuration, not `implementation(...)`. `data-generator-gg8` and `data-generator-gg9` need transitive access to `Instruments` (their flavor `Main`s emit `provisioning.applied` events through the same instance). `OtelSpec` returns from `OpsConfig` so `OpenTelemetry` and `Meter` are part of `core`'s public API surface. Module list:

```kotlin
dependencies {
    api(platform("io.opentelemetry:opentelemetry-bom:1.42.0"))
    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-sdk")
    api("io.opentelemetry:opentelemetry-sdk-metrics")
    api("io.opentelemetry:opentelemetry-sdk-logs")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    // Prometheus exporter is incubator-coordinated until BOM 1.45; explicit version
    // matches the BOM-side incubator track. Re-pin when the BOM stabilises it.
    api("io.opentelemetry:opentelemetry-exporter-prometheus:1.42.0-alpha")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    // ... existing test deps unchanged.
}
```

- [ ] **Step 1: Edit `data-generator-core/build.gradle.kts`** — add the `platform(...)` import line and the six api lines + one testImplementation line above; leave existing entries (`net.datafaker`, Jackson, json-schema-validator, slf4j) untouched.

- [ ] **Step 2: Build**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew :data-generator-core:compileKotlin
```

Expected: BUILD SUCCESSFUL. Resolution should pull `opentelemetry-api-1.42.0`, `opentelemetry-sdk-1.42.0`, etc. Inspect `:data-generator-core:dependencies --configuration compileClasspath | grep opentelemetry` to confirm versions.

- [ ] **Step 3: Verify the existing core suite still passes** — `./gradlew :data-generator-core:test`. ~182 unit tests stay green; OTel deps land but nothing imports them yet. Sanity-check via `:data-generator-core:dependencies --configuration testCompileClasspath | grep opentelemetry` that `sdk-testing` appears under test-only.

- [ ] **Step 4: Commit**

```bash
git add data-generator-core/build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(datagen): add OpenTelemetry SDK + exporters to data-generator-core (Plan 11 Task 1)

Pulls io.opentelemetry:opentelemetry-bom:1.42.0 plus opentelemetry-api,
sdk, sdk-metrics, sdk-logs, exporter-otlp, exporter-prometheus (alpha)
into the core module's api configuration so the gg8/gg9 modules can
share the Instruments registry. Test side gains opentelemetry-sdk-testing
for InMemoryMetricReader + InMemoryLogRecordExporter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Define `OtelSpec` + `OpsConfig.otel` field + JSONSchema entry

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/OtelSpec.kt`
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt`
- Modify: `data-generator-core/src/main/resources/schema/ops/v2.schema.json`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/OtelSpecDeserializationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class OtelSpecDeserializationTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    private val baseScenario = """
        scenarios:
          - name: s1
            target: t1
            root_schemas: [customer]
            rate: { kind: constant, ops_per_second: 1.0 }
            duration: { kind: count, value: 1 }
            read_ratio: 0.0
    """.trimIndent()

    @Test fun `defaults to NONE when otel block is omitted`() {
        val ops = mapper.readValue(
            "schema_version: 2\n$baseScenario",
            OpsConfig::class.java,
        )
        assertThat(ops.otel.exporter).isEqualTo(OtelExporter.NONE)
        assertThat(ops.otel.endpoint).isNull()
        assertThat(ops.otel.attributes).isEmpty()
    }

    @Test fun `parses otlp with endpoint and attributes`() {
        val ops = mapper.readValue("""
            schema_version: 2
            otel:
              exporter: otlp
              endpoint: "http://otel:4318"
              attributes: { deployment.environment: dev }
            $baseScenario
        """.trimIndent(), OpsConfig::class.java)
        assertThat(ops.otel.exporter).isEqualTo(OtelExporter.OTLP)
        assertThat(ops.otel.endpoint).isEqualTo("http://otel:4318")
        assertThat(ops.otel.attributes).containsEntry("deployment.environment", "dev")
    }

    @Test fun `parses prometheus and none enum values`() {
        listOf("prometheus" to OtelExporter.PROMETHEUS, "none" to OtelExporter.NONE).forEach { (yaml, enum) ->
            val ops = mapper.readValue(
                "schema_version: 2\notel: { exporter: $yaml }\n$baseScenario",
                OpsConfig::class.java,
            )
            assertThat(ops.otel.exporter).isEqualTo(enum)
        }
    }
}
```

- [ ] **Step 2: Run from `gridgain-demo-data-generator/` and verify FAIL** — `./gradlew :data-generator-core:test --tests '*OtelSpecDeserializationTest'` (`OtelSpec` doesn't exist).

- [ ] **Step 3: Create `OtelSpec.kt`**

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Top-level `otel:` block in ops.yaml. Default is `OtelSpec.NONE` (exporter = NONE);
 * `OtelInitializer.fromSpec` translates that to `OpenTelemetry.noop()` and every
 * instrument call site degrades to a no-op without raising.
 *
 * NOTE: `attributes` defaults to `emptyMap()` and `endpoint` is nullable in
 * violation of the workspace project rules ("no defaults on template classes" /
 * "no nullable types"). Both are intentional and case-by-case approved:
 *   - `endpoint` is genuinely optional (`exporter: none` ignores it; `prometheus`
 *     defaults to `0.0.0.0:9464` inside `OtelInitializer` if absent).
 *   - `attributes` is an additive resource-attribute bag; forcing every ops.yaml
 *     to spell out an empty map would just be noise.
 * Documented exception, mirroring `ScenarioSpec.transactionScope` / `OpsConfig.targets`.
 */
data class OtelSpec(
    val exporter: OtelExporter,
    val endpoint: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object { val NONE: OtelSpec = OtelSpec(OtelExporter.NONE) }
}

enum class OtelExporter {
    @JsonProperty("none") NONE,
    @JsonProperty("otlp") OTLP,
    @JsonProperty("prometheus") PROMETHEUS,
}
```

- [ ] **Step 4: Modify `OpsConfig.kt`** — add the `otel` field with a default. Keep existing fields untouched:

```kotlin
data class OpsConfig(
    @JsonProperty("schema_version") val schemaVersion: Int,
    val targets: List<TargetSpec> = emptyList(),
    val otel: OtelSpec = OtelSpec.NONE,
    val scenarios: List<ScenarioSpec>,
)
```

- [ ] **Step 5: Add `otel` to v2 ops JSONSchema** — inside `properties` (between `targets` and `scenarios`), add:

```json
"otel": { "$ref": "#/$defs/otel" }
```

…and inside `$defs`, add an `otel` entry:

```json
"otel": {
  "type": "object",
  "required": ["exporter"],
  "additionalProperties": false,
  "properties": {
    "exporter": { "enum": ["none", "otlp", "prometheus"], "default": "none" },
    "endpoint": { "type": "string", "minLength": 1 },
    "attributes": { "type": "object", "additionalProperties": { "type": "string" } }
  }
}
```

- [ ] **Step 6: Verify PASS** — `./gradlew :data-generator-core:test --tests '*OtelSpecDeserializationTest'` plus the broader `:data-generator-core:test` to confirm nothing else broke (Jackson lenient on unknown fields; existing fixtures without `otel` should still deserialize).

- [ ] **Step 7: Commit**

```bash
git add data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/OtelSpec.kt \
        data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/config/OpsConfig.kt \
        data-generator-core/src/main/resources/schema/ops/v2.schema.json \
        data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/config/OtelSpecDeserializationTest.kt
git commit -m "$(cat <<'EOF'
feat(datagen): add OtelSpec + OpsConfig.otel field (Plan 11 Task 2)

Top-level optional `otel:` block in ops.yaml. Three exporter variants
(none|otlp|prometheus); endpoint + attributes optional. Default
OtelSpec.NONE keeps tests fast and offline-runnable. Updates v2 ops
JSONSchema with an additive `otel` def.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `Instruments` — single registration point

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/Instruments.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/observability/InstrumentsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class InstrumentsTest {

    private fun instrumentsWithReader(): Pair<Instruments, InMemoryMetricReader> {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val otel = OpenTelemetrySdk.builder().setMeterProvider(provider).build()
        return Instruments(otel) to reader
    }

    @Test fun `noop instruments do not crash and record nothing`() {
        val instruments = Instruments(OpenTelemetry.noop())
        instruments.opLatency.record(1_000.0, Attributes.empty())
        instruments.opCount.add(1, Attributes.empty())
        instruments.opErrors.add(1, Attributes.empty())
        instruments.inFlight.add(1, Attributes.empty())
        instruments.targetRateRef.set(42.0)
        instruments.observedRateRef.set(7.5)
    }

    @Test fun `histogram name and counter names match spec`() {
        val (instruments, reader) = instrumentsWithReader()
        instruments.opLatency.record(2_500_000.0, Attributes.empty())
        instruments.opCount.add(3, Attributes.empty())
        instruments.opErrors.add(1, Attributes.empty())

        val metrics = reader.collectAllMetrics()
        val names = metrics.map { it.name }
        assertThat(names).contains(
            "data_generator.op.latency",
            "data_generator.op.count",
            "data_generator.op.errors",
        )
    }

    @Test fun `gauges read from AtomicReference slots`() {
        val (instruments, reader) = instrumentsWithReader()
        instruments.targetRateRef.set(123.45); instruments.observedRateRef.set(98.7)
        val names = reader.collectAllMetrics().map { it.name }
        assertThat(names).contains("data_generator.target_rate", "data_generator.observed_rate")
    }
    // Single-registration-point invariant is enforced by the Task 12 visual-checklist grep.
}
```

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Create `Instruments.kt`**

```kotlin
package com.gridgain.demo.datagen.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import java.util.concurrent.atomic.AtomicReference

/**
 * Single registration point for every OTel instrument the generator emits.
 * Spec §7 Extensibility: the instrument list lives here, instrument names are
 * not chosen at call sites. Adding a new instrument is a one-file change here.
 *
 * **No-op safety.** When constructed with `OpenTelemetry.noop()` (the
 * `exporter: none` case) every instrument is the SDK's no-op and records nothing.
 * Production paths never crash on misconfigured OTel — `OtelInitializer` logs
 * the failure once and returns the noop SDK.
 *
 * **Gauge contract.** Asynchronous gauges poll an `AtomicReference<Double>` slot.
 * Callers set values on `targetRateRef` / `observedRateRef`; the SDK reads them
 * on its own collection schedule. A null value (initial state) is reported as 0.0.
 */
class Instruments(otel: OpenTelemetry) {

    companion object {
        const val OP_LATENCY = "data_generator.op.latency"
        const val OP_COUNT = "data_generator.op.count"
        const val OP_ERRORS = "data_generator.op.errors"
        const val IN_FLIGHT = "data_generator.in_flight"
        const val TARGET_RATE = "data_generator.target_rate"
        const val OBSERVED_RATE = "data_generator.observed_rate"

        const val ATTR_SCENARIO = "scenario"
        const val ATTR_TARGET = "target"
        const val ATTR_SCHEMA = "schema"
        const val ATTR_OP = "op"            // put | get | tx_commit | tx_rollback
        const val ATTR_EXCEPTION = "exception"

        /** Convenience for the default scope name across all data-generator instruments. */
        const val SCOPE = "com.gridgain.demo.datagen"

        /** Returns an `Instruments` backed by `OpenTelemetry.noop()` — the production fallback. */
        fun noop(): Instruments = Instruments(OpenTelemetry.noop())
    }

    private val meter: Meter = otel.meterBuilder(SCOPE).build()

    val opLatency: DoubleHistogram = meter.histogramBuilder(OP_LATENCY)
        .setDescription("End-to-end latency of one target op.").setUnit("ns").build()
    val opCount: LongCounter = meter.counterBuilder(OP_COUNT)
        .setDescription("Count of completed target ops.").build()
    val opErrors: LongCounter = meter.counterBuilder(OP_ERRORS)
        .setDescription("Count of failed target ops, tagged by exception class.").build()
    val inFlight: LongUpDownCounter = meter.upDownCounterBuilder(IN_FLIGHT)
        .setDescription("In-flight target ops at this instant.").build()

    val targetRateRef: AtomicReference<Double> = AtomicReference(0.0)
    val observedRateRef: AtomicReference<Double> = AtomicReference(0.0)

    @Suppress("unused") val targetRate: ObservableDoubleGauge = meter.gaugeBuilder(TARGET_RATE)
        .setDescription("Configured target ops/sec (read once at run start).")
        .buildWithCallback { it.record(targetRateRef.get() ?: 0.0) }
    @Suppress("unused") val observedRate: ObservableDoubleGauge = meter.gaugeBuilder(OBSERVED_RATE)
        .setDescription("Achieved ops/sec since run start.")
        .buildWithCallback { it.record(observedRateRef.get() ?: 0.0) }

    /** Builds the `Attributes` for one `tick()` op. Centralised so call sites don't reinvent attr keys. */
    fun opAttributes(scenario: String, target: String, schema: String, op: String): Attributes =
        Attributes.builder().put(ATTR_SCENARIO, scenario).put(ATTR_TARGET, target)
            .put(ATTR_SCHEMA, schema).put(ATTR_OP, op).build()
}
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): Instruments single registration point (Plan 11 Task 3)`. Body: holds histogram (`data_generator.op.latency`), counters (`op.count`, `op.errors`), in-flight up-down counter, and two observable gauges backed by `AtomicReference` slots. Constants for instrument names + attribute keys; no other file types `data_generator.*` strings. Default `Instruments.noop()` for the no-op path. End with the `Co-Authored-By` trailer.

---

### Task 4: `OtelInitializer.fromSpec`

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/OtelInitializer.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/observability/OtelInitializerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gridgain.demo.datagen.observability

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import com.gridgain.demo.datagen.logging.Slf4jDataGenLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.LoggerFactory
import kotlin.test.Test

class OtelInitializerTest {
    private val logger: DataGenLogger = Slf4jDataGenLogger(LoggerFactory.getLogger("test"))

    @Test fun `none returns OpenTelemetry-noop`() {
        val otel = OtelInitializer.fromSpec(OtelSpec(OtelExporter.NONE), logger)
        assertThat(otel).isSameAs(OpenTelemetry.noop())
    }

    @Test fun `otlp and prometheus build a closeable OpenTelemetrySdk`() {
        // Prometheus binds 127.0.0.1:0 so the OS picks an unused port.
        listOf(
            OtelSpec(OtelExporter.OTLP, endpoint = "http://localhost:4318"),
            OtelSpec(OtelExporter.PROMETHEUS, endpoint = "127.0.0.1:0"),
        ).forEach { spec ->
            val otel = OtelInitializer.fromSpec(spec, logger)
            assertThat(otel).isInstanceOf(OpenTelemetrySdk::class.java)
            OtelInitializer.close(otel)
        }
    }

    @Test fun `misconfigured exporters fall back to noop with a warning`() {
        // OTLP without endpoint, Prometheus with garbage host:port — both must noop.
        assertThat(OtelInitializer.fromSpec(OtelSpec(OtelExporter.OTLP, endpoint = null), logger))
            .isSameAs(OpenTelemetry.noop())
        assertThat(OtelInitializer.fromSpec(
            OtelSpec(OtelExporter.PROMETHEUS, endpoint = "not-a-host-port"), logger,
        )).isSameAs(OpenTelemetry.noop())
    }
}
```

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Create `OtelInitializer.kt`**

```kotlin
package com.gridgain.demo.datagen.observability

import com.gridgain.demo.datagen.config.OtelExporter
import com.gridgain.demo.datagen.config.OtelSpec
import com.gridgain.demo.datagen.logging.DataGenLogger
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource

/**
 * Builds an `OpenTelemetry` from an [OtelSpec]. Three modes:
 *
 * - **NONE**  — returns `OpenTelemetry.noop()`; no SDK is allocated.
 * - **OTLP**  — `OpenTelemetrySdk` with OTLP/HTTP metric + log exporters pointed at `endpoint`.
 * - **PROMETHEUS** — `OpenTelemetrySdk` with `PrometheusHttpServer` on `endpoint` (`host:port`).
 *
 * **Failure behaviour.** Any construction error (missing endpoint, unparseable host:port,
 * port already bound) is logged at WARN and returns `OpenTelemetry.noop()`. Production never
 * crashes on misconfigured OTel — that would be worse than running blind.
 *
 * **No GlobalOpenTelemetry registration.** Callers thread the returned instance explicitly so
 * tests can inject `InMemoryMetricReader` / `InMemoryLogRecordExporter` without race conditions
 * across parallel test classes.
 */
object OtelInitializer {

    fun fromSpec(spec: OtelSpec, logger: DataGenLogger): OpenTelemetry {
        return when (spec.exporter) {
            OtelExporter.NONE -> OpenTelemetry.noop()
            OtelExporter.OTLP -> buildOtlp(spec, logger) ?: OpenTelemetry.noop()
            OtelExporter.PROMETHEUS -> buildPrometheus(spec, logger) ?: OpenTelemetry.noop()
        }
    }

    fun close(otel: OpenTelemetry) {
        if (otel is OpenTelemetrySdk) otel.close()
    }

    private fun buildOtlp(spec: OtelSpec, logger: DataGenLogger): OpenTelemetrySdk? {
        val endpoint = spec.endpoint
        if (endpoint.isNullOrBlank()) {
            logger.warn("otel.exporter=otlp requires `endpoint` (e.g., http://collector:4318). " +
                "Falling back to OpenTelemetry.noop(); no metrics or logs will be exported.")
            return null
        }
        return try {
            val resource = buildResource(spec)
            val metricExporter = OtlpHttpMetricExporter.builder().setEndpoint("$endpoint/v1/metrics").build()
            val logExporter = OtlpHttpLogRecordExporter.builder().setEndpoint("$endpoint/v1/logs").build()
            OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().setResource(resource)
                    .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build()).build())
                .setLoggerProvider(SdkLoggerProvider.builder().setResource(resource)
                    .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build()).build())
                .build()
        } catch (e: Exception) {
            logger.warn("otel OTLP init failed: ${e.message}; falling back to OpenTelemetry.noop().", e); null
        }
    }

    private fun buildPrometheus(spec: OtelSpec, logger: DataGenLogger): OpenTelemetrySdk? {
        val raw = spec.endpoint ?: "0.0.0.0:9464"
        val (host, port) = try {
            val idx = raw.lastIndexOf(':'); require(idx > 0) { "expected host:port" }
            raw.substring(0, idx) to raw.substring(idx + 1).toInt()
        } catch (e: Exception) {
            logger.warn("otel.exporter=prometheus endpoint '$raw' is not host:port: ${e.message}; falling back to noop.")
            return null
        }
        return try {
            val reader = PrometheusHttpServer.builder().setHost(host).setPort(port).build()
            OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().setResource(buildResource(spec))
                    .registerMetricReader(reader).build())
                .build()
        } catch (e: Exception) {
            logger.warn("otel Prometheus init failed: ${e.message}; falling back to OpenTelemetry.noop().", e); null
        }
    }

    private fun buildResource(spec: OtelSpec): Resource {
        val builder = Attributes.builder()
            .put(AttributeKey.stringKey("service.name"), "gridgain-demo-data-generator")
        spec.attributes.forEach { (k, v) -> builder.put(AttributeKey.stringKey(k), v) }
        return Resource.getDefault().merge(Resource.create(builder.build()))
    }
}
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): OtelInitializer none/otlp/prometheus dispatch (Plan 11 Task 4)`. Body: returns `OpenTelemetry.noop()` for `NONE`; builds `OpenTelemetrySdk` with OTLP/HTTP exporters or `PrometheusHttpServer` for the others. Misconfig (missing endpoint, unparseable host:port, port-bind failure) falls back to noop with a WARN log — production never crashes on bad OTel. End with `Co-Authored-By` trailer.

---

### Task 5: `LifecycleEvent` sealed hierarchy

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/LifecycleEvent.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/observability/LifecycleEventTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.observability

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Paths
import kotlin.test.Test

class LifecycleEventTest {

    @Test fun `four spec-named events expose the expected name+attributes shape`() {
        val started = LifecycleEvent.ScenarioStarted("customer-load", "gg8")
        assertThat(started.name()).isEqualTo("scenario.started")
        assertThat(started.toAttributes())
            .containsEntry("scenario", "customer-load").containsEntry("target", "gg8")

        val stopped = LifecycleEvent.ScenarioStopped("customer-load", "count reached", 200, 0)
        assertThat(stopped.name()).isEqualTo("scenario.stopped")
        assertThat(stopped.toAttributes()).containsEntry("reason", "count reached")
            .containsEntry("success_count", "200").containsEntry("error_count", "0")

        val provisioned = LifecycleEvent.ProvisioningApplied("gg8", "apply", 0, 2, 1)
        assertThat(provisioned.name()).isEqualTo("provisioning.applied")
        assertThat(provisioned.toAttributes()).containsEntry("flavor", "gg8")
            .containsEntry("mode", "apply").containsEntry("created_count", "2")
            .containsEntry("existed_count", "1")

        val persisted = LifecycleEvent.StatePersisted(Paths.get("/tmp/state.yaml"), 1, 10, 1)
        assertThat(persisted.name()).isEqualTo("state.persisted")
        assertThat(persisted.toAttributes()).containsEntry("state_file", "/tmp/state.yaml")
            .containsEntry("sequence_count", "1").containsEntry("key_count", "10")
    }
}
```

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Create `LifecycleEvent.kt`**

```kotlin
package com.gridgain.demo.datagen.observability

import java.nio.file.Path

/**
 * Lifecycle events emitted around scenario, provisioning, and state-persistence boundaries.
 * Sealed for exhaustive `when` over the concrete cases in `RunLog.emit`. Each event carries
 * a stable `name()` (event name in yaml + OTel log body) and a flat `toAttributes()` map
 * (yaml `attributes:` doc field + OTel log attributes).
 *
 * `name()` values are the spec §7-named events: `scenario.started`, `scenario.stopped`,
 * `provisioning.applied`, `state.persisted`.
 */
sealed class LifecycleEvent {
    abstract fun name(): String
    abstract fun toAttributes(): Map<String, String>

    data class ScenarioStarted(val scenarioName: String, val targetName: String) : LifecycleEvent() {
        override fun name() = "scenario.started"
        override fun toAttributes() = linkedMapOf("scenario" to scenarioName, "target" to targetName)
    }
    data class ScenarioStopped(
        val scenarioName: String, val reason: String, val successCount: Long, val errorCount: Long,
    ) : LifecycleEvent() {
        override fun name() = "scenario.stopped"
        override fun toAttributes() = linkedMapOf(
            "scenario" to scenarioName, "reason" to reason,
            "success_count" to successCount.toString(), "error_count" to errorCount.toString(),
        )
    }
    data class ProvisioningApplied(
        val flavor: String, val mode: String,
        val artifactsWritten: Int, val createdCount: Int, val existedCount: Int,
    ) : LifecycleEvent() {
        override fun name() = "provisioning.applied"
        override fun toAttributes() = linkedMapOf(
            "flavor" to flavor, "mode" to mode,
            "artifacts_written" to artifactsWritten.toString(),
            "created_count" to createdCount.toString(),
            "existed_count" to existedCount.toString(),
        )
    }
    data class StatePersisted(
        val stateFile: Path, val sequenceCount: Int, val keyCount: Int, val runHistorySize: Int,
    ) : LifecycleEvent() {
        override fun name() = "state.persisted"
        override fun toAttributes() = linkedMapOf(
            "state_file" to stateFile.toString(),
            "sequence_count" to sequenceCount.toString(),
            "key_count" to keyCount.toString(),
            "run_history_size" to runHistorySize.toString(),
        )
    }
}
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): LifecycleEvent sealed hierarchy (Plan 11 Task 5)`. Body: four concrete events covering the spec §7 minimum set; flat string-keyed `toAttributes()` so the same shape feeds yaml and OTel logs without divergence. End with `Co-Authored-By` trailer.

---

### Task 6: `RunLog` — multi-doc yaml + OTel log emitter

**Files:**
- Create: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/observability/RunLog.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/observability/RunLogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.observability
// imports: jackson YAMLMapper + KotlinModule, OpenTelemetry/OpenTelemetrySdk,
//          SdkLoggerProvider, SimpleLogRecordProcessor, InMemoryLogRecordExporter,
//          assertj, junit @TempDir, java.nio.file.{Files, Path}, kotlin.test.Test.

class RunLogTest {
    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test fun `emit writes one yaml document per event`(@TempDir dir: Path) {
        val file = dir.resolve("run.log.yaml")
        val log = RunLog(file, otelLogger = null)
        log.emit(LifecycleEvent.ScenarioStarted("customer-load", "gg8"))
        log.emit(LifecycleEvent.ScenarioStopped("customer-load", "count reached", 200, 0))
        log.emit(LifecycleEvent.StatePersisted(dir.resolve("state.yaml"), 1, 10, 1))

        // Three YAML documents = three `---` markers (Jackson's WRITE_DOC_START_MARKER).
        assertThat(Files.readString(file).split("\n---").drop(1)).hasSize(3)
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
```

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Create `RunLog.kt`**

```kotlin
package com.gridgain.demo.datagen.observability

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
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
```

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): RunLog multi-doc yaml + OTel log sink (Plan 11 Task 6)`. Body: appends one yaml document per `LifecycleEvent` to `runs/<run-id>/run.log.yaml`; when an OTel `Logger` is supplied, emits the same payload as a log record. Mirrors the plugin's `YamlEffectSink` shape. End with `Co-Authored-By` trailer.

---

### Task 7: Wire `Instruments` into `ScenarioRunner`

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/target/Target.kt` *(only if `targetName` is not already accessible — see below)*
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerInstrumentsTest.kt` (NEW)

**Decision.** `ScenarioRunner.tick()` is the single instrument call site. Op type for the KV target is `put` for writes and `get` for reads in this plan. `tx_commit` / `tx_rollback` are spec-listed but **deferred to F12** — they require lifting the transaction wrap above the runner (today it's inside `Gg8KvTarget.putAllForEvent` / `Gg9KvTarget.putAllForEvent`). The code structure below leaves room to thread them in without restructuring. The `ScenarioRunner` constructor receives a `targetName: String` (the resolved target name from `Resolution.targetSpec.name`) so attribute building stays inside the runner.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gridgain.demo.datagen.scenario

// Imports: existing ScenarioRunnerTest helpers (scenario(...), dataConfig(),
// trivialGenerator(), InMemoryTarget, etc.) plus:
import com.gridgain.demo.datagen.observability.Instruments
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ScenarioRunnerInstrumentsTest {

    private fun freshInstruments(): Pair<Instruments, InMemoryMetricReader> {
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val otel = OpenTelemetrySdk.builder().setMeterProvider(provider).build()
        return Instruments(otel) to reader
    }

    @Test fun `successful writes record op_count and op_latency under op=put`() {
        val (instruments, reader) = freshInstruments()
        val runner = runner(   // helper from ScenarioRunnerTest
            scenario = scenario(count = 5, readRatio = 0.0),
            target = InMemoryTarget(supportsReads = false, supportsTransactions = false),
            instruments = instruments,
            targetName = "in-memory",
        )
        runner.run()

        val metrics = reader.collectAllMetrics().associateBy { it.name }
        val countSum = metrics.getValue("data_generator.op.count").longSumData.points
            .filter { it.attributes.asMap().any { (k, v) -> k.key == "op" && v == "put" } }
            .sumOf { it.value }
        assertThat(countSum).isEqualTo(5L)

        assertThat(metrics).containsKey("data_generator.op.latency")
        assertThat(metrics).doesNotContainKey("does-not-exist")
    }

    @Test fun `failures record op_errors tagged by exception class`() {
        val (instruments, reader) = freshInstruments()
        val runner = runner(
            scenario = scenario(count = 3, readRatio = 0.0),
            target = AlwaysFailingTarget(IllegalStateException("boom")),
            instruments = instruments,
            targetName = "failing",
        )
        runner.run()

        val errors = reader.collectAllMetrics().first { it.name == "data_generator.op.errors" }
            .longSumData.points
        assertThat(errors).isNotEmpty
        assertThat(errors.first().attributes.asMap()
            .any { (k, v) -> k.key == "exception" && v == "IllegalStateException" }).isTrue()
    }

    @Test fun `target_rate gauge reflects configured constant rate`() {
        val (instruments, reader) = freshInstruments()
        runner(
            scenario = scenario(count = 1, readRatio = 0.0, opsPerSecond = 17.0),
            target = InMemoryTarget(supportsReads = false, supportsTransactions = false),
            instruments = instruments, targetName = "in-memory",
        ).run()
        val tr = reader.collectAllMetrics().first { it.name == "data_generator.target_rate" }
            .doubleGaugeData.points.first().value
        assertThat(tr).isEqualTo(17.0)
    }
}
```

(`AlwaysFailingTarget` and the existing `runner(...)` helper get the two new parameters; no other behavior change.)

- [ ] **Step 2: Verify FAIL** — `instruments` constructor parameter doesn't exist.

- [ ] **Step 3: Modify `ScenarioRunner`**

Constructor adds two parameters with safe defaults:

```kotlin
class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val data: DataConfig,
    private val generator: BusinessEventGenerator,
    private val target: Target,
    private val untilStopCap: Duration = Duration.ofMinutes(1),
    private val decisionRandom: Random = Random(),
    private val keyRegistry: KeyRegistry = KeyRegistry(),
    private val instruments: Instruments = Instruments.noop(),
    private val targetName: String = "<unknown>",
) {
```

Set the `target_rate` gauge once at run start, just before the `started = Instant.now()` line:

```kotlin
val configuredRate: Double = when (val r = scenario.rate) {
    is ConstantRateSpec -> r.opsPerSecond
    is RampedRateSpec -> r.from   // a ramp's starting rate; observed_rate captures real progress
    is SteppedRateSpec -> r.steps.first().rate
}
instruments.targetRateRef.set(configuredRate)
```

Hoist a runner-local `totalAttempts: Long` and a `startedNanos: Long` (both `private var`, set/reset at the top of `run()`) so `tick()` can update `observed_rate` without reading the SDK back. Replace the existing `tick` body — keep the existing read/write branches intact, just add the instrument calls around them:

```kotlin
private fun tick(rateLimiter: RateLimiter, evaluator: StopConditionEvaluator): Boolean {
    rateLimiter.acquire()
    val rootSchemaName = scenario.rootSchemas.first()
    val rootKeyColumn = keyColumnByName[rootSchemaName]!!
    val isRead = scenario.readRatio > 0.0 &&
        target.supportsReads &&
        decisionRandom.nextDouble() < scenario.readRatio &&
        keyRegistry.size(rootSchemaName) > 0
    val op = if (isRead) "get" else "put"
    val attrs = instruments.opAttributes(scenario.name, targetName, rootSchemaName, op)

    instruments.inFlight.add(1, attrs)
    val t0 = System.nanoTime()
    val success: Boolean = try {
        if (isRead) {
            val key = keyRegistry.sample(rootSchemaName, decisionRandom)!!
            target.read(rootSchemaName, key).success
        } else {
            val event = generator.next()
            val rootSchema = schemasByName[rootSchemaName]!!
            val finalEvent = maybeApplyUpdate(event, rootSchema, rootKeyColumn)
            val parentKey = finalEvent.parentRow[rootKeyColumn]!!
            keyRegistry.register(rootSchemaName, parentKey)
            finalEvent.childrenBySchema.forEach { (childSchema, rows) ->
                val childKeyColumn = keyColumnByName[childSchema] ?: return@forEach
                rows.forEach { row -> row[childKeyColumn]?.let { keyRegistry.register(childSchema, it) } }
            }
            target.write(finalEvent).success
        }
    } catch (e: Exception) {
        instruments.opErrors.add(1, attrs.toBuilder()
            .put(Instruments.ATTR_EXCEPTION, e.javaClass.simpleName).build())
        false
    } finally {
        instruments.inFlight.add(-1, attrs)
    }
    val latencyNanos = System.nanoTime() - t0
    instruments.opLatency.record(latencyNanos.toDouble(), attrs)
    instruments.opCount.add(1, attrs)
    if (!success) instruments.opErrors.add(1, attrs.toBuilder()
        .put(Instruments.ATTR_EXCEPTION, "TargetReportedFailure").build())
    evaluator.recordOutcome(success = success, latencyNanos = latencyNanos)

    totalAttempts++
    val elapsedSec = (System.nanoTime() - startedNanos) / 1_000_000_000.0
    if (elapsedSec > 0) instruments.observedRateRef.set(totalAttempts / elapsedSec)
    return success
}
```

Imports added: `com.gridgain.demo.datagen.observability.Instruments`. The runner doesn't need to import `Attributes` directly — `Instruments.opAttributes` returns one and `attrs.toBuilder()` keeps the call sites self-contained.

- [ ] **Step 4: Update `ScenarioRunnerTest` / `ScenarioRunnerExtendedTest` / `ScenarioRunnerKvSemanticsTest` / `ScenarioRunnerCliStateTest`** — every existing `runner(...)` factory or `ScenarioRunner(...)` direct constructor call gets the two new parameters defaulted (no behavior change). Search:

```bash
./gradlew :data-generator-core:compileTestKotlin
```

Fix compile errors by passing the existing tests through the default constructor (no new args needed; defaults cover them).

- [ ] **Step 5: Verify PASS** — `./gradlew :data-generator-core:test --tests '*ScenarioRunnerInstrumentsTest'` plus the full `:data-generator-core:test` suite to catch regressions.

- [ ] **Step 6: Commit** — message: `feat(datagen): instrument ScenarioRunner.tick with OTel histograms/counters/gauges (Plan 11 Task 7)`. Body: per-op `data_generator.op.{latency, count, errors}` keyed by `(scenario, target, schema, op)`; `in_flight` up-down counter wraps each op with try/finally; `target_rate` set once at run start; `observed_rate` updated after each tick. Op type for now is `put`/`get` only — `tx_commit`/`tx_rollback` deferred to F12. End with `Co-Authored-By` trailer.

---

### Task 8: Wire `OtelInitializer`, `Instruments`, `RunLog` into `ScenarioRunnerCli`

**Files:**
- Modify: `data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCli.kt`
- Test: `data-generator-core/src/test/kotlin/com/gridgain/demo/datagen/cli/ScenarioRunnerCliRunLogTest.kt` (NEW)

**Decision.** `Resolution` gains an `openTelemetry: OpenTelemetry` field (built in `resolve()` from `parsedConfig.ops.otel`) and an `instruments: Instruments` field built off it. `RunLog` is built per-run in `run()` because the run-id is only assigned then. `Gg8Main` / `Gg9Main` (Tasks 9 + 10) reach into `Resolution` for the `Instruments` to emit `provisioning.applied`. The OpenTelemetry SDK is closed in a `try { ... } finally { OtelInitializer.close(...) }` around the inner work in `run()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.datagen.cli

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gridgain.demo.datagen.target.InMemoryTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ScenarioRunnerCliRunLogTest {

    @Test fun `run writes a multi-doc run log with started, stopped, and state-persisted`(
        @TempDir dir: Path,
    ) {
        val outputDir = dir.resolve("output").also { Files.createDirectories(it) }
        val args = buildCliArgs(dir = dir, outputDir = outputDir, scenarioName = "writes")
        val logger = ScenarioRunnerCli.defaultLogger()
        val resolution = ScenarioRunnerCli.resolve(args, logger)
        val target = InMemoryTarget(supportsReads = true, supportsTransactions = false)
        ScenarioRunnerCli.run(args, resolution, target, logger)

        val runDirs = Files.list(outputDir.resolve("data-generator/runs")).toList()
        assertThat(runDirs).hasSize(1)
        val runLog = runDirs.first().resolve("run.log.yaml")
        assertThat(Files.exists(runLog)).isTrue()

        val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper
        val docs = mapper.readValues(
            mapper.factory.createParser(runLog.toFile()), Map::class.java,
        ).readAll().filterIsInstance<Map<String, Any>>()
        val events = docs.map { it["event"] as String }
        assertThat(events).containsSubsequence(
            "scenario.started", "scenario.stopped", "state.persisted",
        )
    }
}
```

(`buildCliArgs` reuses the helper from `ScenarioRunnerCliStateTest` (Plan 10 Task 8).)

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Modify `ScenarioRunnerCli`**

Add three fields to `Resolution`: `openTelemetry: OpenTelemetry`, `instruments: Instruments`, `pendingEvents: MutableList<LifecycleEvent> = mutableListOf()`. The third is the buffer Gg{8,9}Main fill from Task 9.

In `resolve()`, just before constructing `Resolution`, build the OTel instance:

```kotlin
val openTelemetry = OtelInitializer.fromSpec(parsedConfig.ops.otel, logger)
val instruments = Instruments(openTelemetry)
return Resolution(
    parsedConfig = parsedConfig, scenario = scenario, targetSpec = targetSpec,
    keyColumnByName = keyColumnByName,
    openTelemetry = openTelemetry, instruments = instruments,
)
```

Modify `run()` with the smallest possible diff against Plan 10's body:

1. Hoist `runId = RunId.generate()` and the layout setup to the top, so the `RunLog` can be built early.
2. Insert immediately after `layout.ensureBaseDirectories()`:

```kotlin
val runId = RunId.generate()
val runLog = RunLog(
    runLogFile = layout.runLogFile(runId),
    otelLogger = resolution.openTelemetry.logsBridge.get(Instruments.SCOPE),
)
resolution.pendingEvents.forEach { runLog.emit(it) }   // drain Gg{8,9}Main events
resolution.pendingEvents.clear()
```

3. Wrap the rest of `run()` in `try { ... } finally { OtelInitializer.close(resolution.openTelemetry) }`.

4. When constructing `ScenarioRunner`, add the two new args:
```kotlin
instruments = resolution.instruments,
targetName = resolution.targetSpec.name,
```

5. Three new emit calls — surround `runner.run()` with started/stopped, and emit `StatePersisted` immediately after `persister.save(newState, layout.stateFile)`:

```kotlin
runLog.emit(LifecycleEvent.ScenarioStarted(
    scenarioName = resolution.scenario.name, targetName = resolution.targetSpec.name,
))
// ... runner.run() ...
runLog.emit(LifecycleEvent.ScenarioStopped(
    scenarioName = resolution.scenario.name,
    reason = result.stopReason,
    successCount = result.successCount,
    errorCount = result.errorCount,
))
// ... persister.save(newState, layout.stateFile) ...
runLog.emit(LifecycleEvent.StatePersisted(
    stateFile = layout.stateFile,
    sequenceCount = newState.sequences.size,
    keyCount = newState.keys.sumOf { it.keys.size },
    runHistorySize = newState.runHistory.size,
))
```

6. Replace `val resultFile = layout.resultFile(runId)` with using the already-hoisted `runId` (delete the duplicate generate call).

The Plan 10 state-load/factory/keyRegistry block, `runner.run()`, `ScenarioResult.write`, `persister.save`, and the trailing `logger.lifecycle(...)` lines are unchanged.

Imports added: `com.gridgain.demo.datagen.observability.Instruments`,
`com.gridgain.demo.datagen.observability.LifecycleEvent`,
`com.gridgain.demo.datagen.observability.OtelInitializer`,
`com.gridgain.demo.datagen.observability.RunLog`,
`io.opentelemetry.api.OpenTelemetry`.

- [ ] **Step 4: Verify PASS** — `./gradlew :data-generator-core:test --tests '*ScenarioRunnerCliRunLogTest'` plus the broader `:data-generator-core:test` to confirm Plan 10 tests still pass with the added parameters.

- [ ] **Step 5: Commit** — message: `feat(datagen): ScenarioRunnerCli builds OTel + emits scenario lifecycle events (Plan 11 Task 8)`. Body: `Resolution` gains `openTelemetry` + `instruments`; `run()` writes a multi-doc `run.log.yaml` covering `scenario.started`, `scenario.stopped`, `state.persisted`. SDK closed in a `finally`. End with `Co-Authored-By` trailer.

---

### Task 9: Emit `provisioning.applied` from `Gg8Main` and `Gg9Main`

**Files:**
- Modify: `data-generator-gg8/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg8Main.kt`
- Modify: `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/cli/Gg9Main.kt`
- Test: `data-generator-gg8/src/test/kotlin/com/gridgain/demo/datagen/cli/Gg8MainProvisioningEventTest.kt` (NEW; uses `@TempDir` against `Gg8XmlProvisioner.emit`, no cluster needed)
- Test: `data-generator-gg9/src/test/kotlin/com/gridgain/demo/datagen/cli/Gg9MainProvisioningEventTest.kt` (NEW; mirror)

**Decision.** `provisioning.applied` is emitted **after** `Provisioner.emit`/`apply` returns and **before** `ScenarioRunnerCli.run()`. The `RunLog` doesn't exist yet at that point (`run()` owns the run-id). Solution: `Resolution.pendingEvents` is a `MutableList<LifecycleEvent>` that Mains append to; `ScenarioRunnerCli.run` drains it right after constructing the `RunLog`. (Alternative: promote run-id into `resolve()`. Rejected — moves a `mkdir` side-effect into resolution.) The buffer field was added in Task 8; Task 9 just adds the `add(...)` calls.

- [ ] **Step 1: Write the failing tests** — for each flavor, assert that running the `Main`'s post-provisioning hook with `provisioning: emit` populates `Resolution.pendingEvents` with one `ProvisioningApplied(flavor = "gg{8,9}", mode = "emit", artifactsWritten = …, createdCount = 0, existedCount = 0)`. Each test uses a minimal in-memory `OpsConfig` + `DataConfig` fixture and exercises the flavor's `Provisioner.emit` against a `@TempDir`.

- [ ] **Step 2: Verify FAIL**.

- [ ] **Step 3: Modify both `Main.main` functions** — after the existing `logger.lifecycle("...provisioning ($mode) ok...")` line, append:

```kotlin
resolution.pendingEvents.add(LifecycleEvent.ProvisioningApplied(
    flavor = "gg8",                          // "gg9" in Gg9Main
    mode = mode.name.lowercase(),
    artifactsWritten = outcome.artifactsWritten.size,
    createdCount = outcome.cachesOrTablesCreated.size,
    existedCount = outcome.cachesOrTablesAlreadyExisted.size,
))
```

Imports: `com.gridgain.demo.datagen.observability.LifecycleEvent`.

- [ ] **Step 4: Verify PASS**.

- [ ] **Step 5: Commit** — message: `feat(datagen): Gg8Main + Gg9Main record provisioning.applied lifecycle event (Plan 11 Task 9)`. Body: post-provisioning, each flavor-specific main appends a `ProvisioningApplied` event to `Resolution.pendingEvents`; `ScenarioRunnerCli.run` drains it into `run.log.yaml` and the OTel logger. End with `Co-Authored-By` trailer.

---

### Task 10: Live-cluster smoke against `taxi-demo-gcp-8a`

**Goal:** Confirm Plan 11 holds against a real GG8 cluster, with metric instruments reachable through `exporter: none` (which is a no-op SDK — sanity check only) and a `run.log.yaml` arriving on disk under TaxiDemo's `build/gridgain/output/data-generator/runs/<run-id>/`.

- [ ] **Step 1: Publish to maven local**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew publishToMavenLocal
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin
./gradlew publishToMavenLocal
```

- [ ] **Step 2: Run with default `exporter: none`, observe run.log.yaml**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/TaxiDemo
./gradlew dataGenerate --scenario customer-load
ls -la build/gridgain/output/data-generator/runs/
cat build/gridgain/output/data-generator/runs/*/run.log.yaml
```

Expected:
- A `runs/<run-id>/` directory exists.
- `run.log.yaml` has at least three documents: `scenario.started`, `scenario.stopped`, `state.persisted`.
- If provisioning is configured for the scenario, a `provisioning.applied` document appears first.
- All documents have an ISO-8601 `timestamp:` field and an `attributes:` map.

- [ ] **Step 3: Optionally run with `exporter: otlp` against a local collector** — only if a collector is reachable; otherwise skip and document. Edit `taxi-demo-gcp-8a`'s `ops.yaml` to add `otel: { exporter: otlp, endpoint: "http://localhost:4318" }` and run again. Expected: same yaml output plus metrics arriving at the collector. Roll back the change before committing.

- [ ] **Step 4: Smoke fail-safe** — set `otel.endpoint` to garbage (`"http://does-not-resolve.invalid:4318"`) and run again. Expected: WARN log mentioning the OTLP fallback, `run.log.yaml` still written (yaml sink is independent of the OTel SDK), scenario completes successfully. Roll back the change.

- [ ] **Step 5: Commit any TaxiDemo `.gitignore` updates** (no expected changes; runs live under `build/`, already ignored). Skip if no diff.

If a live cluster isn't available, document the gap in the final report. Plan 11's correctness does not depend on this — it's a confirmation, not a verification.

---

### Task 11: ROADMAP update + open F12 + F13

**Files:**
- Modify: `gridgain-demo-data-generator/docs/superpowers/ROADMAP.md`

- [ ] **Step 1: Update ROADMAP**
  - Move Plan 11 from "Remaining Plans" into a new "Plan 11 — OpenTelemetry *(complete)*" section above Plan 10, mirroring Plan 10's structure.
  - Bump the **Last updated** line to today's date and the test-count line in **Current State**: `Plans 1–11 implemented. **<bumped>** tests pass (... unit + 8 env-gated integration) ...`. Use the actual `./gradlew clean test` count from Task 12; expect ~205 unit + 8 integration = ~213 total.
  - Remove the "## Remaining Plans" section since Plan 11 was the last; replace with a short note that lists no remaining plans.
  - Add an **F12** entry to "Open Follow-ups":

```
### F12 — `tx_commit` / `tx_rollback` op type emission
*Source: Plan 11 Task 7 design note.*
Spec §7 lists `op = put | get | tx_commit | tx_rollback`. Plan 11 emits `put` and `get` only — the transaction wrap lives inside `Gg{8,9}KvTarget.putAllForEvent`. Emitting `tx_commit` / `tx_rollback` requires lifting the transaction boundary into `ScenarioRunner.tick` or threading `Instruments` into the flavor targets. Pick a path when `business_event` traffic on a customer scenario makes the gap material.
```

  - Add an **F13** entry: `Plugin endpoint inheritance for OTel`. Source: Plan 11 spec §7 deferral. Spec §7 says the generator inherits the OTel endpoint from a plugin-declared Prometheus/Grafana monitor. Plan 11 ships standalone `ops.yaml`-driven OTel only. Inheritance requires (a) plugin-side: surface the monitor's endpoint to `DataGenerateTask`; (b) generator-side: a CLI flag (e.g. `--otel-endpoint-override`) that wins over `ops.otel`. Capture the contract before implementing so plugin and generator ship in lock-step.

- [ ] **Step 2: Commit** — message: `docs(datagen): roadmap — Plan 11 complete; F12 + F13 opened (Plan 11 Task 11)`. End with `Co-Authored-By` trailer.

---

### Task 12: Final verification

- [ ] **Step 1: Full clean build + test**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-data-generator
./gradlew clean test
```

Expected: BUILD SUCCESSFUL across all three subprojects. ~205 unit tests green. 8 env-gated integration tests skip when env vars are absent (unchanged from Plan 10).

- [ ] **Step 2: Visual review checklist**

- `observability/` package carries 4 files (`Instruments`, `OtelInitializer`, `LifecycleEvent`, `RunLog`); `OtelSpec.kt` declares the data class + enum; `OpsConfig.otel` defaults to `OtelSpec.NONE`.
- `Instruments.kt` is the only producer of the six `data_generator.*` instrument names — verify with `git grep -n 'data_generator\\.' -- '*.kt' | grep -v Instruments.kt | grep -v test/` (expect zero hits).
- `ScenarioRunner` accepts `instruments` + `targetName` constructor params; `tick` wraps every op with `inFlight.add(+1)/add(-1)` in try/finally and records `opLatency`/`opCount`; on exception or `success = false`, records `opErrors` with `exception` attribute.
- `ScenarioRunner.run` sets `targetRateRef` once and updates `observedRateRef` every tick.
- `ScenarioRunnerCli.Resolution` exposes `openTelemetry`, `instruments`, `pendingEvents`; `ScenarioRunnerCli.run` emits `scenario.started`/`stopped`/`state.persisted`, drains `pendingEvents` for `provisioning.applied`, closes the SDK in `finally`.
- `Gg8Main` / `Gg9Main` each append exactly one `ProvisioningApplied` event to `pendingEvents` after their `Provisioner.emit`/`apply`.
- No `org.gradle.*` imports in `data-generator-core`. No `GlobalOpenTelemetry.set(...)` anywhere. SnakeYAML pin at `1.33` unchanged.
- Spec §7 minimum-viable instrument set: histogram (1) + counters (2) + gauges (3) + lifecycle events (4) all present.

- [ ] **Step 3: Run `superpowers:requesting-code-review`** (subagent-driven step)

---

## Self-review

- [ ] All commits end with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- [ ] No `org.gradle.*` or `org.apache.ignite.*` imports in `data-generator-core`.
- [ ] One nullable introduced (`OtelSpec.endpoint`) — documented inline against the "no nullable" rule.
- [ ] No `GlobalOpenTelemetry.set(...)` anywhere; every consumer takes `OpenTelemetry` explicitly.
- [ ] No new deps beyond OpenTelemetry BOM + six modules + `sdk-testing` for tests; SnakeYAML stays at `1.33`.
- [ ] `OutputLayout.runLogFile(runId)` is the sole `run.log.yaml` path producer.
- [ ] Instrument names live only on `Instruments.kt` (Task 12 grep).
- [ ] Lifecycle `name()` strings match spec §7: `scenario.started`, `scenario.stopped`, `provisioning.applied`, `state.persisted`.
- [ ] `RunLog.emit` writes yaml unconditionally; OTel log emission gates on a non-null `Logger`. Spec §7 mandates the yaml file regardless of exporter.

## Spec coverage audit

| Spec § | Tasks |
|---|---|
| §7 `otel` block in ops.yaml | 2 |
| §7 minimum viable instruments — histograms | 3, 7 |
| §7 minimum viable instruments — counters | 3, 7 |
| §7 minimum viable instruments — gauges | 3, 7 |
| §7 lifecycle events as OTel logs | 5, 6, 8, 9, 10 |
| §7 `run.log.yaml` multi-doc yaml | 6, 8 |
| §7 single-registration-point extensibility | 3 |
| §7 dashboard reuse / endpoint inheritance | F13 (deferred) |
| §13 verification step 5 (latency histograms appear in OTel exporter) | 7, 11 |

**Out of scope (recorded as F12 / F13):** `tx_commit` / `tx_rollback` op type
emission; plugin Prometheus/Grafana endpoint inheritance; bespoke sliding-window
`observed_rate` computation (today: cumulative `total / elapsed`); cohort-attribute
tagging on op metrics (today: `(scenario, target, schema, op)` only).

## Critical files (forward references)

- `data-generator-core/.../observability/Instruments.kt` — the one place to add a new instrument or attribute key. Future plans (e.g., F12 `tx_commit`/`tx_rollback`) extend this without touching `ScenarioRunner` beyond the `op` argument.
- `data-generator-core/.../observability/RunLog.kt` — extension point for new lifecycle events; F13's plugin-endpoint-override scenario will likely emit a new `otel.endpoint.inherited` event.
- `data-generator-core/.../observability/OtelInitializer.kt` — landing spot for additional exporter modes (e.g., a stdout exporter for local debugging) and for the `--otel-endpoint-override` CLI flag from F13.
- `data-generator-core/.../cli/ScenarioRunnerCli.kt` — `Resolution.pendingEvents` is the agreed pattern for any other pre-`run()` lifecycle event source (e.g., a future `data-validation.applied` event from cross-element validation).
