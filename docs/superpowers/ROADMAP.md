# Data Generator — Roadmap

Durable record of in-flight work, deferred follow-ups, and remaining plans for
`gridgain-demo-data-generator`. Survives Claude Code session boundaries.

Last updated: 2026-05-05 (after F12 closure — tx_commit/tx_rollback emission)

---

## Current State

Plans 1–11 implemented. **221 tests pass** (213 unit + 8 env-gated integration)
across three subprojects: `data-generator-core` (config, generators,
scenario, output, provisioning plan factory, observability), `data-generator-gg8`
(`Gg8KvTarget` + `Gg8XmlProvisioner` with env-gated integration tests),
`data-generator-gg9` (`Gg9KvTarget` + `Gg9SqlProvisioner` with env-gated
integration tests). After Plan 9 every scenario carries `provisioning: skip|emit|apply`;
the data generator can render GG8 cache XML, GG9 SQL DDL, and create absent
caches/tables idempotently. The `affinity: true` annotation is finally consumed.
The data generator can:

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
- Write to a real GG9 cluster via `Gg9KvTarget` (lazy `IgniteClient`,
  `KeyValueView<Tuple, Tuple>`, optional transaction wrapping when
  `transaction_scope: business_event`).
- Persist `state.yaml` across runs (Plan 10): per-schema sequence cursors,
  per-schema emitted-key snapshots, and the run history index. Hard-fail on
  schema_version mismatch — no migration support, mirroring the plugin's
  `deployment.yaml` rule. State writes are atomic via `state.yaml.tmp` →
  `Files.move(... ATOMIC_MOVE)`.

Plugin's `DataGenerateTask` dispatches the fork's classpath + main class per
the resolved target's kind: `gg8-kv` → `dataGeneratorGg8Runtime` + `Gg8Main`;
`gg9-kv` → `dataGeneratorGg9Runtime` + `Gg9Main`.

GG8 path verified end-to-end against the live `taxi-demo-gcp-8a` cluster
via the plugin (Plan 8 + re-verified after Plan 7.5):
`success_count: 200, error_count: 0, achieved_rate: ~8.1 ops/s,
stop_reason: count reached`.

---

## Open Follow-ups (deferred fixes from final reviews)

These are tracked work items, not full plans. Each is small and can be picked
up in any order.

### F7 — `ScenarioTargetValidator` softness when `ops.targets` empty
*Source: Plan 6 final review.*
The validator unconditionally rejects `target = ""` for every scenario. Today
this is fine because tests with scenarios always set targets explicitly. As
Plan 7 adds `Gg9KvTargetSpec` and we get more test fixtures, a fragility bug
might surface. Decision needed: keep strict (current) or short-circuit when
`ops.targets` is empty?

### F10 — Provisioning SqlType inference's coarse defaults
*Source: Plan 9 review.*
`ProvisioningPlanFactory.inferType` maps every non-`SequenceSpec` value source
to `SqlType.VARCHAR`, including `WeightedChoiceSpec` whose choices may be
numeric. `Gg9SqlDdlRenderer` widens VARCHAR to `VARCHAR(256)`. Both are safe
defaults but a future plan should:
(a) infer from the runtime type of `WeightedChoiceSpec.choices[0].value`,
(b) parameterize VARCHAR length per column,
(c) extend `SqlType` to cover timestamp / decimal / numeric.

### F13 — Plugin endpoint inheritance for OTel
*Source: Plan 11 spec §7 deferral.*
Spec §7 says the generator inherits the OTel endpoint from a plugin-declared
Prometheus/Grafana monitor. Plan 11 ships standalone `ops.yaml`-driven OTel
only. Inheritance requires (a) plugin-side: surface the monitor's endpoint
to `DataGenerateTask`; (b) generator-side: a CLI flag (e.g.
`--otel-endpoint-override`) that wins over `ops.otel`. Capture the contract
before implementing so plugin and generator ship in lock-step.

---

## Closed Follow-ups

### F5 — `BusinessEvent.parentSchemaName` ✅ *(closed 2026-05-03)*
`BusinessEvent` now carries an explicit `parentSchemaName: String`.
`BusinessEventGenerator.next()` sets it from `rootSchema.name`.
`Gg8KvTarget.putAllForEvent` and `Gg9KvTarget.putAllForEvent` look up the
parent key column directly via `keyColumnByName[event.parentSchemaName]` —
no more back-infer-from-key-column-name heuristic.

### F8 — Plugin `DataGenerateTask` classpath split for GG8 vs GG9 ✅ *(closed by Plan 7.5)*
Plan 7.5 split the data-generator into three subprojects (`-core`, `-gg8`,
`-gg9`). The plugin now declares two configurations
(`dataGeneratorGg8Runtime`, `dataGeneratorGg9Runtime`), pre-parses ops.yaml
to learn the resolved target's kind, and dispatches the fork classpath +
main class accordingly.

### F9 — Eliminate reflection workaround in `Gg9KvTarget` ✅ *(closed by Plan 7.5)*
With GG8's `ignite-core` no longer on the `data-generator-gg9` module's
classpath, the FQN ambiguity disappears at the build-tool level.
`gg9Tables()` and `gg9Transactions()` reflection helpers were removed in
Plan 7.5 Task 5; `client.tables()` and `client.transactions()` now resolve
directly against GG9's `IgniteClient`.

### F6 — TRANSACTIONAL cache provisioning ✅ *(closed by Plan 9)*
Plan 9 Task 8 sets `CacheAtomicityMode.TRANSACTIONAL` on caches whose
descriptors carry `transactional = true` (driven by `transaction_scope:
business_event`). `Gg8KvTarget.putRow` keeps using `getOrCreateCache(name)`
because the cache now exists with the right mode — provisioning owns the
cache-shape decision.

### F1 — `OutputLayout.ensureBaseDirectories` is-directory guard ✅ *(closed 2026-05-03)*
`ensureDir` checks for a regular file at the target path before
`Files.createDirectories` and throws `CorruptedStateException` with
remediation. Also catches the silent-success case after the call. Plan 10
(state persistence) can now rely on `stateFile`'s parent.

### F2 — `WeightedChoice` cumulative-picker boundary ✅ *(closed 2026-05-03)*
`cumulative.first { r < it.first }` replaced with
`cumulative.firstOrNull { r < it.first } ?: cumulative.last()`. Boundary
graceful even if a misbehaving `Random` reaches `totalWeight`.

### F3 — `YamlBackedValueSource` seed correlation ✅ *(closed 2026-05-03)*
`ValueSourceFactory.buildCore` now takes the full `ColumnSpec` and seeds
`YamlBackedValueSource` with `Random(seed + column.name.hashCode())`,
mirroring `KeySuffixValueSource`. Two yaml-backed columns in the same
schema no longer draw identical sequences. `WeightedChoiceValueSource`
got the same per-column decorrelation as a defensive bonus.

### F4 — `BusinessEventGenerator` multi-FK silent first-wins ✅ *(closed 2026-05-05)*
New `MultiFkToSameParentValidator` rejects schemas with two or more
`parent-fk-ref` columns pointing at the same parent. Multi-FK to *different*
parents stays valid. Wired into `CompositeCrossElementValidator` between
`NullRateOnRelationColumnValidator` and `CohortBucketSharesValidator`.

### F11 — `KeyRegistry` persisted-key type fidelity ✅ *(closed 2026-05-05)*
`KeyRegistryState` gained a `keyType` discriminator (`LONG | STRING`).
`KeyRegistry.snapshot` infers the type from runtime keys (per-schema
homogeneity enforced); `restore` coerces yaml strings back via
`String.toLong()` for LONG, leaves STRING as-is. `state.yaml`
`schema_version` bumped 1 → 2 — no migration; v1 files fail-load with
remediation per spec §6. Added spec §13 step 7 restart-then-read test
(`ScenarioRunnerCliRestartReadTest`) confirming `Long` round-trip.

### F12 — `tx_commit` / `tx_rollback` op type emission ✅ *(closed 2026-05-05)*
`WriteOutcome` carries a new `transactionOutcome: TransactionOutcome`
(`NONE | COMMITTED | ROLLED_BACK`). `Gg8KvTarget` populates it from the
explicit `txStart`/`commit`/`rollback` flow; `Gg9KvTarget` populates it
from the `runInTransaction` lambda's success vs. exception path (the SDK
auto-rolls-back when the lambda throws). `ScenarioRunner.tick` reads
the outcome and emits `op=tx_commit` or `op=tx_rollback` as separate
points on `data_generator.op.{count, latency}`, alongside the existing
`op=put`. Latency for the tx op uses the wall time of the wrapped event
— a meaningful proxy for "how long committed/rolled-back transactions
take" in aggregate. Targets keep owning the transaction lifecycle — no
architectural shift.

---

## Plan 11 — OpenTelemetry *(complete)*

Per spec §7. Optional top-level `otel: { exporter: none|otlp|prometheus,
endpoint, attributes }` block in `ops.yaml`. Default `exporter: none`
returns `OpenTelemetry.noop()` so existing fixtures and tests stay
offline-runnable.

- **Single registration point.** `observability/Instruments.kt` owns every
  metric: histogram (`data_generator.op.latency`), counters
  (`op.count`, `op.errors`), and three gauges (`in_flight`, `target_rate`,
  `observed_rate`). Adding a new instrument is a one-file change.
- **OtelInitializer.fromSpec** dispatches NONE/OTLP/PROMETHEUS, falling
  back to noop with a WARN on misconfig — production never crashes on
  bad OTel.
- **ScenarioRunner.tick** records latency + count per op, error counter
  tagged by exception class, in-flight up-down counter wraps each op
  with try/finally. Op type is `put`/`get` only — `tx_commit`/`tx_rollback`
  deferred (F12).
- **Lifecycle events** (`scenario.started`, `scenario.stopped`,
  `provisioning.applied`, `state.persisted`) flow through `RunLog` —
  multi-doc yaml at `runs/<run-id>/run.log.yaml` plus optional OTel log
  records when an `otelLogger` is supplied. `Resolution.pendingEvents`
  buffers `provisioning.applied` from `Gg{8,9}Main` since it fires
  before the `RunLog` exists.
- **No GlobalOpenTelemetry registration anywhere.** Every consumer takes
  an `OpenTelemetry` instance explicitly so tests can inject
  `InMemoryMetricReader` / `InMemoryLogRecordExporter`.

Live-cluster smoke verified against `taxi-demo-gcp-8a` with
`provisioning: emit` + `read_ratio: 0.10`: four-doc `run.log.yaml`
covering `provisioning.applied → scenario.started → scenario.stopped →
state.persisted`, ISO timestamps, attribute maps. 194 successes,
6 errors out of 200 (stale-key reads against fresh keys).

All 12 tasks done — see `plans/2026-05-05-data-generator-plan-11-opentelemetry.md`.
Opens follow-ups F12 (`tx_commit`/`tx_rollback` emission) and F13 (plugin
endpoint inheritance).

## Plan 10 — State Persistence *(complete)*

Per spec §6. `state.yaml` now persists per-schema sequence cursors,
key-emission snapshots, and the run history index across `dataGenerate`
invocations. Lives at `<demoOutputDirectory>/data-generator/state/state.yaml`.

- `StatePersister.load` returns `null` on first run, throws
  `CorruptedStateException` with remediation on `schemaVersion` mismatch
  or corrupt yaml — no migration support, mirroring the plugin's
  `deployment.yaml` rule.
- `StatePersister.save` writes via `state.yaml.tmp` + `Files.move(...
  ATOMIC_MOVE, REPLACE_EXISTING)`. Falls back to non-atomic move when the
  filesystem rejects ATOMIC_MOVE.
- `ScenarioRunnerCli.run` does load → seed (factory + KeyRegistry) → run
  → snapshot → save in that order.

In-process integration test (`ScenarioRunnerCliStateTest`) verifies
sequences continue across two runs against `InMemoryTarget`: run 1 emits
ids 1–10, run 2 picks up at 11 and ends at 21 with 20 registered keys
and two run-history entries.

Live-cluster smoke deferred — `taxi-demo-gcp-8a` was unreachable when
attempted; the in-process test covers the persistence pipeline.

All 12 tasks done — see `plans/2026-05-04-data-generator-plan-10-state-persistence.md`.
Opens follow-up F11 (KeyRegistry persisted-key type fidelity).

## Plan 9 — Provisioning Emit + Apply *(complete)*

Per spec §4. Adds per-scenario `provisioning: skip|emit|apply`. `emit`
writes GG8 cache XML to `<outputDir>/data-generator/provisioning/gg8/` and
GG9 SQL DDL to `.../provisioning/gg9/`. `apply` creates absent
caches/tables on the cluster idempotently. The `affinity: true` annotation
is finally consumed (GG8 `keyConfiguration`; GG9 `COLOCATE BY`). Closes
F6 (TRANSACTIONAL cache mode); opens F10 (SqlType inference refinement).

All 15 tasks done — see `plans/2026-05-03-data-generator-plan-9-provisioning.md`.

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

## Plan 7.5 — Subproject Split *(complete)*

The data-generator is now a multi-project gradle build mirroring
`gridgain-demo-client-utils`:

- `data-generator-core` — GG-agnostic engine (config, generators, scenario,
  `Target` interface, `InMemoryTarget`, CLI plumbing). No `ignite-*` deps.
- `data-generator-gg8` — `Gg8KvTarget` + `Gg8Main`. Depends on core +
  `gg8-client-finder` + `ignite-core:8.9.18`.
- `data-generator-gg9` — `Gg9KvTarget` + `Gg9Main`. Depends on core +
  `gg9-client-finder` + `ignite-client:9.1.3`.

Maven coordinates after split:
- `com.gridgain.demo:gridgain-demo-data-generator-core:0.0.1-SNAPSHOT`
- `com.gridgain.demo:gridgain-demo-data-generator-gg8:0.0.1-SNAPSHOT`
- `com.gridgain.demo:gridgain-demo-data-generator-gg9:0.0.1-SNAPSHOT`

Closes F8 (plugin per-target classpath dispatch) and F9 (reflection workaround
in `Gg9KvTarget` removed because GG8's `ignite-core` is no longer on its
compile classpath). All 11 tasks done — see
`plans/2026-05-03-data-generator-subproject-split.md`.

## Plan 7 — GG9 KV Target *(complete)*

`Gg9KvTarget` lives at `data-generator-gg9/src/main/kotlin/com/gridgain/demo/datagen/target/Gg9KvTarget.kt`.
Lazy `IgniteClient` via `gg9-client-finder.DemoAddressFinder`; KV access through
`KeyValueView<Tuple, Tuple>`; optional `business_event` transaction wrapping via
`runInTransaction { tx -> ... }`. `Gg9Main` constructs the target; the plugin
dispatches it via `dataGeneratorGg9Runtime`. Env-gated integration tests for
write + read.

All 9 tasks done — see `plans/2026-05-03-data-generator-plan-7-gg9-kv-target.md`.
Live-cluster GG9 smoke is now unblocked (Plan 7.5 closed F8); pending operator
bring-up of a GG9 cluster.

## Remaining Plans

No remaining plans drafted in spec §1–§11. Future direction lives in
spec §12 (Future Work — per-scenario read-skew override, compute-task
workloads, pluggable transaction-scope policies, end-user JVM custom
providers, multi-cluster targeting, etc.) and is intentionally
unscheduled.

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
