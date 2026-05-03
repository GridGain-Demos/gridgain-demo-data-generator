# Data Generator — Plan 5: Scenario Engine Completion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the scenario engine's runtime by wiring the deferred rate kinds (ramped, stepped), latency tracking + latency-based stop conditions, and the `until_stop_condition` duration. Add the `affinity: true` column annotation as a v2 additive field (used by Plan 6+ provisioning; ignored at runtime). End deliverable: every spec'd scenario primitive that does NOT require a real cluster is fully runnable against `InMemoryTarget`.

**Architecture:** Replace the `Plan 5` deferral throws in `ScenarioRunner.buildRateLimiter()` and `StopConditionEvaluator` with real implementations. `RampedRateLimiter` interpolates the rate linearly between `from` and `to` over the `over` duration, then holds at `to`. `SteppedRateLimiter` walks through the steps, holding each at its `rate` for `hold` and advancing to the next step on schedule. `LatencyHistogram` records every write's nanos and answers `quantile(p)` cheaply (sorted snapshot — fine for benchmark scale). The `StopConditionEvaluator` accepts the latency histogram on each `recordOutcome(success, latencyNanos)` and evaluates `latency_p99_above` / `latency_p999_above` thresholds. The `until_stop_condition` duration delegates entirely to the evaluator: the runner loops until `evaluator.shouldStop()` returns non-null. `affinity: Boolean = false` becomes an annotation on `ColumnSpec`; v2 JSONSchema accepts it as an optional field. No runtime path consumes it yet.

**Tech Stack:** Same as Plan 4. No new dependencies.

---

## Spec extensions to v2

```yaml
# data.yaml — additive: affinity flag on a column
schema_version: 2
schemas:
  - name: customer
    update_ratio: 0.0
    columns:
      - name: id
        null_rate: 0.0
        affinity: true            # <-- new optional field, defaults to false
        value_source: { kind: sequence, start: 1, step: 1 }

# ops.yaml — runnable rate variants and stop conditions:
schema_version: 2
scenarios:
  - name: ramp-then-hold
    root_schemas: [customer]
    rate: { kind: ramped, from: 10, to: 1000, over: PT30S }
    duration: { kind: time, value: PT60S }
    stop_conditions:
      - { kind: latency_p99_above, threshold: PT0.05S }
    transaction_scope: none
    read_ratio: 0.0

  - name: until-broken
    root_schemas: [customer]
    rate: { kind: stepped, steps: [{ rate: 100, hold: PT5S }, { rate: 1000, hold: PT5S }] }
    duration: { kind: until_stop_condition }
    stop_conditions:
      - { kind: error_rate_above, threshold: 0.05 }
    transaction_scope: none
    read_ratio: 0.0
```

---

## File Structure

```
gridgain-demo-data-generator/
├── src/main/kotlin/com/gridgain/demo/datagen/
│   ├── config/
│   │   └── DataConfig.kt                # add affinity: Boolean = false to ColumnSpec
│   └── scenario/
│       ├── RateLimiter.kt               # add RampedRateLimiter, SteppedRateLimiter
│       ├── LatencyHistogram.kt          # NEW: in-memory latency recorder + quantile
│       ├── StopConditionEvaluator.kt    # extend with latency support; remove Plan-5 throws
│       └── ScenarioRunner.kt            # wire all of the above
├── src/main/resources/schema/
│   └── data/v2.schema.json              # accept optional `affinity: boolean`
└── src/test/kotlin/com/gridgain/demo/datagen/
    ├── config/AffinityFieldDeserializationTest.kt
    └── scenario/
        ├── RampedRateLimiterTest.kt
        ├── SteppedRateLimiterTest.kt
        ├── LatencyHistogramTest.kt
        ├── StopConditionEvaluatorLatencyTest.kt
        └── ScenarioRunnerExtendedTest.kt
```

---

### Task 1: Add `affinity` field to ColumnSpec + JSONSchema

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`
- Modify: `src/main/resources/schema/data/v2.schema.json` — add `affinity` to `column.properties` (NOT to `required`).
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/config/AffinityFieldDeserializationTest.kt`

`affinity` is additive: existing v2 yaml without it stays valid because Jackson defaults the field. Per the project rule against template-class defaults, this is the one place we need to allow a default — the field must round-trip without forcing every existing config file to add `affinity: false` to every column. Document this exception in the data class KDoc.

- [ ] **Step 1: Update ColumnSpec**

In `src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt`, change `ColumnSpec` to:

```kotlin
/**
 * NOTE: `affinity` carries a default value of false in violation of the workspace project rule
 * "no defaults on template classes". This exception is intentional and limited: Plan 5 introduces
 * the field as a forward-compat annotation for provisioning (Plan 6+). Forcing every existing
 * column declaration to opt in explicitly would require migrating every demoConfigFile in the
 * wild for no behavioral benefit. Plan 6 may revisit this when provisioning starts to consume it.
 */
data class ColumnSpec(
    val name: String,
    @JsonProperty("null_rate") val nullRate: Double,
    val affinity: Boolean = false,
    @JsonProperty("value_source") val valueSource: ValueSourceSpec,
)
```

- [ ] **Step 2: Update v2 JSONSchema**

In `src/main/resources/schema/data/v2.schema.json`, in `$defs.column.properties`, add:

```json
"affinity": { "type": "boolean" }
```

Do NOT add `affinity` to `column.required` — it remains optional.

- [ ] **Step 3: Write the deserialization test**

`src/test/kotlin/com/gridgain/demo/datagen/config/AffinityFieldDeserializationTest.kt`:

```kotlin
package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AffinityFieldDeserializationTest {

    private val mapper = YAMLMapper().registerKotlinModule() as YAMLMapper

    @Test
    fun `affinity defaults to false when omitted`() {
        val yaml = """
            name: id
            null_rate: 0.0
            value_source:
              kind: sequence
              start: 1
              step: 1
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.affinity).isFalse()
    }

    @Test
    fun `affinity is read when present`() {
        val yaml = """
            name: id
            null_rate: 0.0
            affinity: true
            value_source:
              kind: sequence
              start: 1
              step: 1
        """.trimIndent()
        val column: ColumnSpec = mapper.readValue(yaml, ColumnSpec::class.java)
        assertThat(column.affinity).isTrue()
    }
}
```

- [ ] **Step 4: Run targeted tests + full suite**

`./gradlew test --tests 'com.gridgain.demo.datagen.config.AffinityFieldDeserializationTest'` — 2 PASS.
`./gradlew test` — full suite green, 108 tests (106 prior + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/config/DataConfig.kt src/main/resources/schema/data/v2.schema.json src/test/kotlin/com/gridgain/demo/datagen/config/AffinityFieldDeserializationTest.kt
git commit -m "feat(datagen): add optional affinity flag to ColumnSpec (additive in v2)"
```

Sign with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` trailer.

---

### Task 2: RampedRateLimiter

**Files:**
- Modify (append): `src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/RampedRateLimiterTest.kt`

Linear interpolation from `fromOpsPerSecond` to `toOpsPerSecond` over `rampDuration`, then steady at `to`. The limiter records the `rampStartedNanos` at construction; each `acquire()` computes the current rate, derives `intervalNanos`, and schedules the next allowed time.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/RampedRateLimiterTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class RampedRateLimiterTest {

    @Test
    fun `ramp from 50 to 200 over 200ms then hold yields about 30-50 ops in 250ms`() {
        val limiter = RampedRateLimiter(
            fromOpsPerSecond = 50.0,
            toOpsPerSecond = 200.0,
            rampDuration = Duration.ofMillis(200),
        )
        val start = System.nanoTime()
        var count = 0
        while ((System.nanoTime() - start) < 250_000_000L) {
            limiter.acquire()
            count++
        }
        // Average rate over 250ms: ramp avg = (50+200)/2 = 125 for first 200ms = 25 ops,
        // then 50ms at 200 ops/s = 10 ops; total ~35 ops. Allow a wide band for jitter.
        assertThat(count).isBetween(20, 60)
    }

    @Test
    fun `after the ramp window the rate is steady at to`() {
        val limiter = RampedRateLimiter(
            fromOpsPerSecond = 10.0,
            toOpsPerSecond = 1000.0,
            rampDuration = Duration.ofMillis(50),
        )
        // Burn the ramp window
        Thread.sleep(60)
        val start = System.nanoTime()
        repeat(50) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 50 ops at 1000/s ≈ 50ms; allow [40, 200].
        assertThat(elapsedMs).isBetween(40L, 200L)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Append to `src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt`:

```kotlin
import java.time.Duration

class RampedRateLimiter(
    private val fromOpsPerSecond: Double,
    private val toOpsPerSecond: Double,
    rampDuration: Duration,
) : RateLimiter {
    private val rampDurationNanos: Long = rampDuration.toNanos()
    private val rampStartedNanos: Long = System.nanoTime()
    private var nextAllowedNanos: Long = rampStartedNanos

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        }
        val effectiveNow = maxOf(nextAllowedNanos, now)
        val elapsed = effectiveNow - rampStartedNanos
        val rate: Double = if (elapsed >= rampDurationNanos) {
            toOpsPerSecond
        } else {
            val t: Double = elapsed.toDouble() / rampDurationNanos
            fromOpsPerSecond + t * (toOpsPerSecond - fromOpsPerSecond)
        }
        val intervalNanos: Long = (1_000_000_000.0 / rate).toLong()
        nextAllowedNanos = effectiveNow + intervalNanos
    }
}
```

- [ ] **Step 4: Run — 2 PASS.** Timing tests can flap on slow runners; if they fail outside the bands, widen to `[15, 80]` and `[35, 300]` and report DONE_WITH_CONCERNS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/RampedRateLimiterTest.kt
git commit -m "feat(datagen): add RampedRateLimiter with linear interpolation"
```

---

### Task 3: SteppedRateLimiter

**Files:**
- Modify (append): `src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/SteppedRateLimiterTest.kt`

Walks through `(rate, hold)` steps. After all steps complete, hold at the last step's rate.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/SteppedRateLimiterTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class SteppedRateLimiterTest {

    @Test
    fun `walks two steps and holds at the last`() {
        val limiter = SteppedRateLimiter(
            steps = listOf(
                StepConfig(rate = 100.0, hold = Duration.ofMillis(100)),
                StepConfig(rate = 500.0, hold = Duration.ofMillis(50)),
            ),
        )
        // First 100ms: 100/s ≈ 10 ops; next 50ms: 500/s ≈ 25 ops; +50ms held at 500/s ≈ 25 ops.
        // Total in 200ms: ~60 ops.
        val start = System.nanoTime()
        var count = 0
        while ((System.nanoTime() - start) < 200_000_000L) {
            limiter.acquire()
            count++
        }
        assertThat(count).isBetween(40, 90)
    }

    @Test
    fun `single step behaves like a constant rate`() {
        val limiter = SteppedRateLimiter(
            steps = listOf(StepConfig(rate = 200.0, hold = Duration.ofSeconds(10))),
        )
        val start = System.nanoTime()
        repeat(50) { limiter.acquire() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 50 ops @ 200/s ≈ 250ms.
        assertThat(elapsedMs).isBetween(200L, 400L)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Append to `src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt`:

```kotlin
data class StepConfig(val rate: Double, val hold: Duration)

class SteppedRateLimiter(steps: List<StepConfig>) : RateLimiter {

    init {
        if (steps.isEmpty()) {
            throw com.gridgain.demo.datagen.errors.MisconfigurationException(
                "stepped rate limiter requires at least one step; received zero. " +
                "Add at least one entry under 'steps'."
            )
        }
    }

    private data class Boundary(val endNanos: Long, val intervalNanos: Long)
    private val rampStartedNanos: Long = System.nanoTime()
    private val boundaries: List<Boundary>
    private val finalIntervalNanos: Long
    private var nextAllowedNanos: Long = rampStartedNanos

    init {
        var cumNanos = 0L
        val list = mutableListOf<Boundary>()
        for (s in steps) {
            cumNanos += s.hold.toNanos()
            list.add(Boundary(endNanos = rampStartedNanos + cumNanos, intervalNanos = (1_000_000_000.0 / s.rate).toLong()))
        }
        boundaries = list
        finalIntervalNanos = (1_000_000_000.0 / steps.last().rate).toLong()
    }

    override fun acquire() {
        val now = System.nanoTime()
        val sleep = nextAllowedNanos - now
        if (sleep > 0) {
            Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        }
        val effectiveNow = maxOf(nextAllowedNanos, now)
        val intervalNanos: Long = boundaries.firstOrNull { effectiveNow < it.endNanos }?.intervalNanos
            ?: finalIntervalNanos
        nextAllowedNanos = effectiveNow + intervalNanos
    }
}
```

- [ ] **Step 4: Run — 2 PASS.** Same timing-jitter caveat as Task 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/RateLimiter.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/SteppedRateLimiterTest.kt
git commit -m "feat(datagen): add SteppedRateLimiter walking step boundaries"
```

---

### Task 4: LatencyHistogram

**Files:**
- Create: `src/main/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogram.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogramTest.kt`

Records each operation's latency in nanos and answers `quantile(p): Long?` (null when no samples yet). Plan 5 uses an `ArrayList<Long>` snapshot — fine at benchmark scale (under a few million events). A future plan may swap in HdrHistogram or a similar streaming-percentile structure if memory becomes a concern.

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogramTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class LatencyHistogramTest {

    @Test
    fun `quantile is null when no samples`() {
        val h = LatencyHistogram()
        assertThat(h.quantile(0.99)).isNull()
    }

    @Test
    fun `p99 of 1..100 is approximately 99`() {
        val h = LatencyHistogram()
        for (i in 1L..100L) h.record(i)
        assertThat(h.quantile(0.99)).isEqualTo(99L)
    }

    @Test
    fun `p999 of a uniform 1..1000 is approximately 999`() {
        val h = LatencyHistogram()
        for (i in 1L..1000L) h.record(i)
        assertThat(h.quantile(0.999)).isEqualTo(999L)
    }

    @Test
    fun `quantile p is rejected when out of range`() {
        val h = LatencyHistogram()
        h.record(1L)
        org.assertj.core.api.Assertions.assertThatThrownBy { h.quantile(1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`src/main/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogram.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

class LatencyHistogram {

    private val samples: MutableList<Long> = mutableListOf()

    fun record(nanos: Long) { samples.add(nanos) }

    fun quantile(p: Double): Long? {
        require(p in 0.0..1.0) { "quantile p must be in [0, 1]; got $p" }
        if (samples.isEmpty()) return null
        val sorted = samples.toLongArray().also { it.sort() }
        val rank = (p * (sorted.size - 1)).toInt()
        return sorted[rank]
    }
}
```

- [ ] **Step 4: Run — 4 PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogram.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/LatencyHistogramTest.kt
git commit -m "feat(datagen): add LatencyHistogram with quantile snapshot"
```

---

### Task 5: StopConditionEvaluator latency support

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluator.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorLatencyTest.kt`

Drop the Plan-5-deferred throws for `LatencyP99StopSpec` and `LatencyP999StopSpec`. Update `recordOutcome` to take a latency in nanos. Update `shouldStop` to consult a `LatencyHistogram`.

- [ ] **Step 1: Replace `StopConditionEvaluator.kt`**

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.ExternalSignalStopSpec
import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import com.gridgain.demo.datagen.config.LatencyP999StopSpec
import com.gridgain.demo.datagen.config.StopConditionSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.time.Duration

class StopConditionEvaluator(private val conditions: List<StopConditionSpec>) {

    init {
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec, is LatencyP99StopSpec, is LatencyP999StopSpec -> Unit  // supported
                is ExternalSignalStopSpec -> throw MisconfigurationException(
                    "Stop condition kind 'external_signal' is designed but not implemented in this build. " +
                    "A future plan will wire external-signal stop conditions."
                )
            }
        }
    }

    private var successCount: Long = 0
    private var failureCount: Long = 0
    private val latency: LatencyHistogram = LatencyHistogram()

    fun recordOutcome(success: Boolean, latencyNanos: Long = 0L) {
        if (success) successCount++ else failureCount++
        if (latencyNanos > 0) latency.record(latencyNanos)
    }

    fun shouldStop(): String? {
        val total = successCount + failureCount
        if (total < 100) return null
        val errorRate = failureCount.toDouble() / total
        for (c in conditions) {
            when (c) {
                is ErrorRateStopSpec -> {
                    if (errorRate > c.threshold) {
                        return "error_rate exceeded threshold: $errorRate > ${c.threshold}"
                    }
                }
                is LatencyP99StopSpec -> {
                    val q = latency.quantile(0.99) ?: continue
                    val thresholdNanos = Duration.parse(c.threshold).toNanos()
                    if (q > thresholdNanos) {
                        return "latency_p99 exceeded threshold: ${q}ns > ${thresholdNanos}ns"
                    }
                }
                is LatencyP999StopSpec -> {
                    val q = latency.quantile(0.999) ?: continue
                    val thresholdNanos = Duration.parse(c.threshold).toNanos()
                    if (q > thresholdNanos) {
                        return "latency_p999 exceeded threshold: ${q}ns > ${thresholdNanos}ns"
                    }
                }
                is ExternalSignalStopSpec -> Unit  // never reached; rejected at construction
            }
        }
        return null
    }
}
```

- [ ] **Step 2: Update existing StopConditionEvaluatorTest**

The existing `StopConditionEvaluatorTest` test `unsupported stop condition kind is rejected at construction` had two clauses — one for `LatencyP99StopSpec`, one for `ExternalSignalStopSpec`. The latency clause must be removed. Update to:

```kotlin
@Test
fun `unsupported stop condition kind is rejected at construction`() {
    assertThatThrownBy { StopConditionEvaluator(listOf(ExternalSignalStopSpec())) }
        .isInstanceOf(MisconfigurationException::class.java)
        .hasMessageContaining("external_signal")
}
```

(The `LatencyP99StopSpec` and `LatencyP999StopSpec` are now accepted; the test that asserted they throw must be removed or split.)

- [ ] **Step 3: Write the new latency test class**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorLatencyTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.LatencyP99StopSpec
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class StopConditionEvaluatorLatencyTest {

    @Test
    fun `latency p99 above threshold triggers stop`() {
        val e = StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.001S"))) // 1ms
        // Below threshold for 99 samples
        repeat(99) { e.recordOutcome(success = true, latencyNanos = 100_000L) } // 0.1ms
        // One sample at 100ms — well above threshold
        e.recordOutcome(success = true, latencyNanos = 100_000_000L)
        // Need 100 total samples, each is recorded; p99 of [100us..100us..100ms] is 100ms.
        val reason = e.shouldStop()
        assertThat(reason).isNotNull
        assertThat(reason!!).contains("latency_p99")
    }

    @Test
    fun `latency p99 below threshold does not trigger`() {
        val e = StopConditionEvaluator(listOf(LatencyP99StopSpec("PT0.1S"))) // 100ms
        repeat(100) { e.recordOutcome(success = true, latencyNanos = 1_000_000L) } // 1ms each
        assertThat(e.shouldStop()).isNull()
    }
}
```

- [ ] **Step 4: Run targeted tests + full suite**

`./gradlew test --tests 'com.gridgain.demo.datagen.scenario.StopConditionEvaluatorLatencyTest' --tests 'com.gridgain.demo.datagen.scenario.StopConditionEvaluatorTest'` — all PASS.
`./gradlew test` — full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluator.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorTest.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/StopConditionEvaluatorLatencyTest.kt
git commit -m "feat(datagen): wire latency_p99 and latency_p999 stop conditions"
```

---

### Task 6: ScenarioRunner — wire ramped/stepped + latency + until_stop

**Files:**
- Modify: `src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`
- Test:   `src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerExtendedTest.kt`

Changes:
1. `buildRateLimiter()` now handles `RampedRateSpec` and `SteppedRateSpec`.
2. The runner records per-write latency on `recordOutcome(success, latencyNanos)`.
3. `UntilStopDurationSpec` is supported: loop until `evaluator.shouldStop()` returns non-null. Bound by a hard cap of 1 minute by default to prevent test runaways — Plan 7+ may make the cap configurable.

- [ ] **Step 1: Update ScenarioRunner**

Replace `src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ConstantRateSpec
import com.gridgain.demo.datagen.config.CountDurationSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.target.Target
import java.time.Duration
import java.time.Instant

class ScenarioRunner(
    private val scenario: ScenarioSpec,
    private val generator: BusinessEventGenerator,
    private val target: Target,
    /** Hard ceiling for `until_stop_condition` durations to prevent test runaways. */
    private val untilStopCap: Duration = Duration.ofMinutes(1),
) {
    fun run(): ScenarioResult {
        val rateLimiter = buildRateLimiter()
        val evaluator = StopConditionEvaluator(scenario.stopConditions)
        val started = Instant.now()
        var success = 0L
        var error = 0L
        var stopReason = ""

        when (val d = scenario.duration) {
            is CountDurationSpec -> {
                while (success + error < d.value) {
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "count reached"
            }
            is TimeDurationSpec -> {
                val targetDuration = Duration.parse(d.value)
                while (Duration.between(started, Instant.now()) < targetDuration) {
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "time elapsed"
            }
            is UntilStopDurationSpec -> {
                while (Duration.between(started, Instant.now()) < untilStopCap) {
                    val outcome = doOne(rateLimiter, evaluator)
                    if (outcome.success) success++ else error++
                    val triggered = evaluator.shouldStop()
                    if (triggered != null) { stopReason = triggered; break }
                }
                if (stopReason.isEmpty()) stopReason = "until_stop_condition cap reached"
            }
        }

        val wall = Duration.between(started, Instant.now())
        val achievedRate = if (wall.toNanos() > 0)
            (success + error).toDouble() / (wall.toNanos() / 1_000_000_000.0) else 0.0
        return ScenarioResult(
            scenarioName = scenario.name,
            achievedRate = achievedRate,
            errorCount = error,
            successCount = success,
            stopReason = stopReason,
            wallTime = wall,
        )
    }

    private data class OneOutcome(val success: Boolean)

    private fun doOne(rateLimiter: RateLimiter, evaluator: StopConditionEvaluator): OneOutcome {
        rateLimiter.acquire()
        val t0 = System.nanoTime()
        val outcome = target.write(generator.next())
        val latencyNanos = System.nanoTime() - t0
        evaluator.recordOutcome(success = outcome.success, latencyNanos = latencyNanos)
        return OneOutcome(success = outcome.success)
    }

    private fun buildRateLimiter(): RateLimiter = when (val r = scenario.rate) {
        is ConstantRateSpec -> ConstantRateLimiter(r.opsPerSecond)
        is RampedRateSpec -> RampedRateLimiter(
            fromOpsPerSecond = r.from,
            toOpsPerSecond = r.to,
            rampDuration = Duration.parse(r.over),
        )
        is SteppedRateSpec -> SteppedRateLimiter(
            steps = r.steps.map { StepConfig(rate = it.rate, hold = Duration.parse(it.hold)) },
        )
    }
}
```

- [ ] **Step 2: Update existing ScenarioRunnerTest**

The existing test `unsupported rate kind is rejected` expected `RampedRateSpec` to throw with "Plan 5". That throw is now gone — the rate is supported. Replace this test with one that confirms the new rate kinds run.

In `ScenarioRunnerTest.kt`, remove the `unsupported rate kind is rejected` test entirely (its assertion no longer holds — the runner accepts the rate now). The new ramped/stepped/until-stop coverage moves to `ScenarioRunnerExtendedTest`.

- [ ] **Step 3: Write the new test class**

`src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerExtendedTest.kt`:

```kotlin
package com.gridgain.demo.datagen.scenario

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.ErrorRateStopSpec
import com.gridgain.demo.datagen.config.RampedRateSpec
import com.gridgain.demo.datagen.config.RateStep
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.ScenarioSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import com.gridgain.demo.datagen.config.SteppedRateSpec
import com.gridgain.demo.datagen.config.TimeDurationSpec
import com.gridgain.demo.datagen.config.TransactionScope
import com.gridgain.demo.datagen.config.UntilStopDurationSpec
import com.gridgain.demo.datagen.generation.BusinessEvent
import com.gridgain.demo.datagen.generation.BusinessEventGenerator
import com.gridgain.demo.datagen.generation.ValueSourceFactory
import com.gridgain.demo.datagen.target.InMemoryTarget
import com.gridgain.demo.datagen.target.Target
import com.gridgain.demo.datagen.target.WriteOutcome
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

class ScenarioRunnerExtendedTest {

    private fun simpleData() = DataConfig(2, listOf(
        SchemaSpec("customer", 0.0, listOf(ColumnSpec("id", 0.0, valueSource = SequenceSpec(1, 1))))
    ))

    private fun runner(dir: Path, scenario: ScenarioSpec, target: Target = InMemoryTarget()): ScenarioRunner {
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L)
        val gen = BusinessEventGenerator(simpleData(), "customer", factory, Faker(), cohortSeed = 1L)
        return ScenarioRunner(scenario = scenario, generator = gen, target = target,
            untilStopCap = Duration.ofMillis(500))
    }

    @Test
    fun `ramped rate runs to completion`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "ramped-test",
            rootSchemas = listOf("customer"),
            rate = RampedRateSpec(from = 100.0, to = 1000.0, over = "PT0.1S"),
            duration = TimeDurationSpec("PT0.2S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.successCount).isGreaterThan(20L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `stepped rate runs to completion`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "stepped-test",
            rootSchemas = listOf("customer"),
            rate = SteppedRateSpec(listOf(
                RateStep(rate = 200.0, hold = "PT0.05S"),
                RateStep(rate = 500.0, hold = "PT0.05S"),
            )),
            duration = TimeDurationSpec("PT0.1S"),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.successCount).isGreaterThan(15L)
        assertThat(result.stopReason).isEqualTo("time elapsed")
    }

    @Test
    fun `until_stop_condition runs until the cap`(@TempDir dir: Path) {
        val scenario = ScenarioSpec(
            name = "until-stop-no-stop",
            rootSchemas = listOf("customer"),
            rate = com.gridgain.demo.datagen.config.ConstantRateSpec(opsPerSecond = 100.0),
            duration = UntilStopDurationSpec(),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario).run()
        assertThat(result.stopReason).isEqualTo("until_stop_condition cap reached")
        assertThat(result.wallTime.toMillis()).isBetween(450L, 800L)
    }

    @Test
    fun `until_stop_condition stops on error_rate trigger`(@TempDir dir: Path) {
        val target = object : Target {
            override val supportsReads: Boolean = false
            override val supportsTransactions: Boolean = false
            private var n = 0
            override fun write(event: BusinessEvent): WriteOutcome {
                n++
                // First 100 successes (need 100 samples for evaluator), then all failures.
                return if (n <= 100) WriteOutcome(success = true) else WriteOutcome(success = false, error = RuntimeException("boom"))
            }
        }
        val scenario = ScenarioSpec(
            name = "until-stop-on-error",
            rootSchemas = listOf("customer"),
            rate = com.gridgain.demo.datagen.config.ConstantRateSpec(opsPerSecond = 1000.0),
            duration = UntilStopDurationSpec(),
            stopConditions = listOf(ErrorRateStopSpec(threshold = 0.05)),
            transactionScope = TransactionScope.NONE,
            readRatio = 0.0,
        )
        val result = runner(dir, scenario, target).run()
        assertThat(result.stopReason).contains("error_rate")
        assertThat(result.errorCount).isGreaterThan(0L)
    }
}
```

- [ ] **Step 4: Run targeted tests**

`./gradlew test --tests 'com.gridgain.demo.datagen.scenario.ScenarioRunnerExtendedTest' --tests 'com.gridgain.demo.datagen.scenario.ScenarioRunnerTest'` — all PASS.

- [ ] **Step 5: Run full suite**

`./gradlew test` — `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunner.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerTest.kt src/test/kotlin/com/gridgain/demo/datagen/scenario/ScenarioRunnerExtendedTest.kt
git commit -m "feat(datagen): wire ramped/stepped rate, latency tracking, until_stop to ScenarioRunner"
```

---

### Task 7: Full-suite green check

- [ ] `./gradlew clean test` — `BUILD SUCCESSFUL`. Total tests after Plan 5: roughly 121 (106 prior + 2 affinity + 2 ramped + 2 stepped + 4 latency + 2 latency-stop + 4 extended runner — minus the deleted `unsupported rate kind is rejected` test = +14, total ≈ 120). Accept whatever count is green.

- [ ] `./gradlew clean build` — `BUILD SUCCESSFUL`.

---

## Verification (end-to-end smoke)

1. Author a v2 ops.yaml with a ramped rate scenario and a `latency_p99_above` stop condition. Pair with the v2 customer-order data fixture.
2. Parse via `ConfigurationParser`; confirm cross-element validation passes.
3. Run via `ScenarioRunner` against `InMemoryTarget`. Inspect `result.yaml`:
   - `achieved_rate` is in the same order of magnitude as the ramped end rate.
   - `stop_reason` is one of `time elapsed`, `count reached`, `until_stop_condition cap reached`, or a triggered stop reason text.
4. Mutate the scenario to `until_stop_condition` with a `latency_p99_above` threshold of `PT0.0001S` (100µs). Re-run; expect a triggered stop with `latency_p99` in the reason.

---

## Spec Coverage Audit

| Spec § | Covered by |
|--------|------------|
| §2 ramped rate | Tasks 2, 6 |
| §2 stepped rate | Tasks 3, 6 |
| §2 latency_p99_above / latency_p999_above | Tasks 4, 5, 6 |
| §2 until_stop_condition duration | Task 6 |
| §1 affinity column annotation | Task 1 |
| Project rule: rich error messages | Tasks 3, 5 |

**Out of scope of this plan (deferred):**
- Real KV targets (GG8, GG9) — Plan 6.
- Transaction wrapping of business events — Plan 6.
- update_ratio execution + key registry — Plan 6.
- Reads execution — Plan 6.
- external_signal stop condition — needs an external coordination plane, deferred.
- Provisioning emit + apply — Plan 7.
- State persistence — Plan 7.
- OTel — Plan 8.
- CLI + plugin invocation — Plan 8.
