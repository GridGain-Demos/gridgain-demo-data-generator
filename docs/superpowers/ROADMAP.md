# Data Generator — Roadmap

Durable record of in-flight work, deferred follow-ups, and remaining plans for
`gridgain-demo-data-generator`. Survives Claude Code session boundaries.

Last updated: 2026-05-03 (after Plan 8 end-to-end smoke ✅ via plugin)

---

## Current State

Plans 1–6 implemented. **142 tests pass** from a clean build (139 unit + 3
env-gated integration tests against a GG8 cluster). The data generator can:

- Parse and migrate two yaml configs (`data.yaml` + `ops.yaml`) via the five-stage
  pipeline (read → migrate → JSONSchema → cross-element → deserialize).
- Generate per-row data via DataFaker stock providers + 4 built-in
  extension providers (sequence, unique, weighted-choice, yaml-data).
- Generate parent + cohort-distributed children via `parent-fk-ref` relations
  and the `BusinessEventGenerator`.
- Drive scenarios at constant/ramped/stepped rates, with time/count/until-stop
  durations and error-rate/latency-p99/latency-p999 stop conditions.
- Track per-write latency; emit `result.yaml` with achieved rate, errors, stop
  reason, wall time.
- Hold a per-schema `KeyRegistry` so `update_ratio` can re-emit existing keys
  and `read_ratio` can pick keys to read.
- Write to a real GG8 cluster via `Gg8KvTarget` (lazy thin client, optional
  transaction wrapping when `transaction_scope: business_event`).

Verified end-to-end against the live `taxi-demo-gcp-8a` cluster.

---

## Open Follow-ups (deferred fixes from final reviews)

These are tracked work items, not full plans. Each is small and can be picked
up in any order.

### F1 — `OutputLayout.ensureBaseDirectories` is-directory guard
*Source: Plan 1 final review.*
`Files.createDirectories` succeeds silently when a regular file (not a
directory) sits at the expected path, leading to cryptic downstream errors.
Add an explicit check that throws `CorruptedStateException` with remediation.
Address before Plan 10 (state persistence) starts writing to `stateFile`.

### F2 — `WeightedChoice` cumulative-picker boundary
*Source: Plan 2 final review.*
`cumulative.first { r < it.first }` would throw `NoSuchElementException` if
`Random.nextDouble()` ever reached `totalWeight`. The JDK contract guarantees
`[0.0, 1.0)`, so it can't happen today, but the code's correctness depends on
that contract. Replace with `firstOrNull { r < it.first } ?: cumulative.last()`.

### F3 — `YamlBackedValueSource` seed correlation
*Source: Plan 3 final review.*
`ValueSourceFactory` passes `Random(seed)` to `YamlBackedValueSource`, so two
yaml-backed columns in the same schema draw identical sequences.
`KeySuffixValueSource` uses `Random(seed + spec.baseColumn.hashCode())` —
apply the same pattern to yaml-backed.

### F4 — `BusinessEventGenerator` multi-FK silent first-wins
*Source: Plan 3 final review.*
If a child schema has two `parent-fk-ref` columns pointing to the same parent
(syntactically valid), only the first column's cohort buckets are honored.
Either add a validator rejecting multi-FK-to-same-parent, or document the
first-wins semantics explicitly.

### F5 — `BusinessEvent.parentSchemaName` for `Gg8KvTarget` heuristic
*Source: Plan 6 final review + Plan 6 Task 10 design note.*
`Gg8KvTarget.write` uses a heuristic (back-infer parent schema from key column
name) to find which cache to put to. Fragile when two schemas share a key
column name like `id`. The clean fix: extend `BusinessEvent` to carry
`parentSchemaName: String` explicitly. Then drop the heuristic.

### F6 — TRANSACTIONAL cache provisioning for `transaction_scope: business_event`
*Source: Plan 6 Gg8KvTarget integration discussion.*
GG8 8.9+ rejects atomic-cache operations inside transactions. When a scenario
opts into `transaction_scope: business_event`, every target cache must be
configured `CacheAtomicityMode.TRANSACTIONAL`. Today `Gg8KvTarget.putRow` calls
`getOrCreateCache(name)` which defaults to ATOMIC. Either: (a) build a
`ClientCacheConfiguration` with TRANSACTIONAL mode in `putRow`, OR (b) wait
for Plan 9 (provisioning emit/apply) to create caches with the right mode and
just document the requirement here. Path (b) is preferable — it keeps the
target dumb and pushes the cache-shape decision to the provisioning layer.

### F7 — `ScenarioTargetValidator` softness when `ops.targets` empty
*Source: Plan 6 final review.*
The validator unconditionally rejects `target = ""` for every scenario. Today
this is fine because tests with scenarios always set targets explicitly. As
Plan 7 adds `Gg9KvTargetSpec` and we get more test fixtures, a fragility bug
might surface. Decision needed: keep strict (current) or short-circuit when
`ops.targets` is empty?

---

## Plan 8 — Plugin Invocation *(complete)*

`cd TaxiDemo && ./gradlew dataGenerate --scenario <name>` runs end-to-end
through the plugin. Smoke verified on `taxi-demo-gcp-8a`:
`success_count: 200, error_count: 0, achieved_rate: ~7.9 ops/s, stop_reason: count reached`.

All seven tasks done — see `plans/2026-05-03-data-generator-plan-8-plugin-invocation.md`.
Two late fixes that landed during smoke:
- Data-generator `cli/Main.kt` needed `@file:JvmName("Main")` so the forked
  JVM could find the main class (Kotlin compiles top-level `main` to `MainKt`
  by default).
- Plugin `DataGenerateTask` `@Option` annotations had to move from
  `@get:Option` to `@set:Option` — Gradle 9 only treats annotated methods that
  take a parameter as value-options.

## Remaining Plans (not yet drafted)

Each plan produces working, testable software on its own. Order is flexible.

### Plan 7 — GG9 KV Target
Mirror Plan 6 for GridGain 9. Different transaction API (single `IgniteClient`
that handles both KV and SQL; `KeyValueView` instead of `cache`). Adds
`Gg9KvTargetSpec` to `TargetSpec`, `Gg9KvTarget` runtime, and one new
`is Gg9KvTargetSpec ->` branch each in `ValueSourceFactory.capabilitiesFor`.
Integration tests env-gated by `DATAGEN_GG9_*`. Roughly the same shape and
size as Plan 6.

### Plan 9 — Provisioning Emit + Apply
Per spec §4. Generates GG8 cache config XML and GG9 SQL DDL from `data.yaml`
schemas; optionally applies them to the cluster. Closes follow-up F6
(TRANSACTIONAL cache mode). `affinity: true` column annotation finally consumed
here. Two artifact formats:
- GG8 — cache `<bean>` XML with `affinityKey` and `atomicityMode`.
- GG9 — SQL `CREATE ZONE` + `CREATE TABLE … COLOCATE BY (...)`.

### Plan 10 — State Persistence
The `KeyRegistry`, `RunId` history, and per-schema sequence positions become
persistent across runs. Writes to `demoOutputDirectory/data-generator/state/state.yaml`
with its own `schemaVersion` (no migration — mismatch is a hard error per the
plugin's deployment-state pattern). Closes follow-up F1 (isDirectory guard
becomes load-bearing here).

### Plan 11 — OpenTelemetry
Per spec §7. Instrument the runner with OTel histograms (`data_generator.op.latency`),
counters (`op.count`, `op.errors`), gauges (`in_flight`, `target_rate`,
`observed_rate`), and lifecycle log events. Single registration point so the
instrument list is owned in one place. Optional `otel: { exporter, endpoint }`
block in `ops.yaml`.

---

## Operating Modes

### Standalone (used during Plans 1–6)
```
cd gridgain-demo-data-generator
./gradlew test                                                    # unit tests
DATAGEN_GG8_CLUSTER_NAME=… DATAGEN_GG8_TEST_CACHE=… \
  GG_DEMO_CLIENT_ENDPOINTS=… ./gradlew test --tests '…'           # integration
```

### Plugin-driven (target after Plan 8)
```
cd .                                                              # workspace root
cd TaxiDemo && ./gradlew dataGenerate --scenario customer-load   # runs via plugin
```

The plugin reads its own `demoConfigFile` to find the cluster's
`client-endpoints.yaml`; the user no longer needs to set `GG_DEMO_CLIENT_ENDPOINTS`
explicitly because the plugin already knows where it wrote the file.

### Maven-local publish workflow
The data-generator must be on the maven-local cache for the plugin to consume
it. After any code change to the data-generator that the plugin needs to see:

```
cd gridgain-demo-data-generator
./gradlew publishToMavenLocal       # populates ~/.m2/repository/com/gridgain/demo/...
cd ..
```

The same pattern applies to `gridgain-demo-client-utils`. The plugin/template
projects already use this convention (per `CLAUDE.md`'s SnapShot caching rule).

---

## Spec & Plan Trail

All plans are durable in this repo:

- `CLAUDE.md` — the capabilities-toned spec (top of project).
- `docs/superpowers/plans/2026-05-02-data-generator-foundation.md` — Plan 1.
- `docs/superpowers/plans/2026-05-02-data-generator-plan-2-data-shape.md` — Plan 2.
- `docs/superpowers/plans/2026-05-02-data-generator-plan-3-cohorts-and-relations.md` — Plan 3.
- `docs/superpowers/plans/2026-05-02-data-generator-plan-4-scenario-engine.md` — Plan 4.
- `docs/superpowers/plans/2026-05-02-data-generator-plan-5-scenario-completion.md` — Plan 5.
- `docs/superpowers/plans/2026-05-02-data-generator-plan-6-gg8-kv-target.md` — Plan 6.
- `docs/superpowers/plans/2026-05-03-data-generator-plan-8-plugin-invocation.md` — Plan 8 (drafted, not executed).

Plans 7, 9, 10, 11 will land here when drafted.

---

## Quick-start for a fresh session

If you're picking this up in a new Claude Code session:

1. Read this `ROADMAP.md`.
2. Read `CLAUDE.md` (the spec).
3. Read the most recent plan doc you intend to execute.
4. Continue from where the roadmap leaves off.

The session-local task list disappears between sessions — this doc is the
durable record.
