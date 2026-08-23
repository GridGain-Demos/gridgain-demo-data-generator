---
name: gridgain-demo-data-generator
description: How to USE the GridGain demo data generator — authoring ops.yaml/data.yaml, choosing rate kinds, transaction_scope, distribution (multi-pod), provisioning, live metrics, and runtime rate control. Use when configuring or running a data-generator scenario, debugging generator throughput/errors, driving load up/down at runtime, deciding how to load a GridGain cluster, or editing ops.yaml/data.yaml. Standalone component — it has no dependency on the gradle plugin or any demo.
---

# GridGain Demo Data Generator — Usage

*Last updated: 2026-08-23*

A YAML-configured streaming data generator for GridGain 8/9 clusters. It is a **standalone** component (consumed by the plugin and the demo UI, but depends on neither). This skill is the usage contract: the config surface and the semantics that bite. It does **not** describe how any particular consumer launches it — for the gradle plugin's `dataGenerate` dispatch, see the `gridgain-demo-toolkit` skill.

> **Verify before asserting.** Flags, enum values and version numbers drift. The config-file *shape* below is the stable contract; cite the source files in §Sources when a fact must be exact, and re-check against them. Keep the *Last updated* date current when you change this file.

## Two config files

| File | Purpose | Current schema_version |
|------|---------|------------------------|
| `ops.yaml` | scenarios (rate/duration/scope), metrics, control, otel | **7** |
| `data.yaml` | schemas → columns → value sources, FK relations | **2** |

Both carry a `schema_version` and are **auto-migrated** forward before validation (`OpsConfigMigrationRunner`, `DataConfigMigrationRunner`). Validation failures are fatal with remediation text.

## ops.yaml

```yaml
schema_version: 7
metrics:                       # optional — live throughput/latency to Kafka (§Metrics)
  kafka_bootstrap: "kafka:9092"
  topic: "datagen-metrics"
  interval_ms: 1000
  histogram_highest_ms: 60000        # required from v6 — latency histogram ceiling
  histogram_significant_digits: 3    # required from v6 — HdrHistogram precision (1-4)
control:                       # optional (v5+) — runtime rate control in (§Runtime control)
  kafka_bootstrap: "kafka:9092"
  topic: "datagen-control"
otel:                          # optional — OpenTelemetry export (exporter: none|otlp|prometheus)
  exporter: otlp
  endpoint: http://collector:4317
scenarios:
  - name: load
    root_schemas: [customer]   # emitted with their transitive children (parent-fk-ref)
    rate: { kind: constant, ops_per_second: 1000 }
    duration: { kind: time, value: "PT10M" }   # ISO-8601 Duration (see gotcha)
    read_ratio: 0.20           # 0.0–1.0 fraction of ops that are reads
    transaction_scope: none    # none | business_event (see gotcha)
    provisioning: skip         # skip | emit | apply
    distribution:              # optional — multi-pod (see gotcha)
      replicas: 4
      partition_count: 16
```

**A scenario names no cluster.** It describes a *load shape* only, which is what makes one ops file portable across demos. The cluster comes from `--target-cluster <name>` at launch (§CLI), resolved against the client-endpoints file. Before v7 a top-level `targets:` array declared `{name, kind, cluster_name}` and each scenario carried a `target:` naming one; `MigrateOpsV6toV7` strips both (§Gotchas). The `kind` discriminator went with them — it was never information the entry point lacked, since `Gg8Main`/`Gg9Main` each serve exactly one flavour.

**`rate` kinds:** `constant` (`ops_per_second`) · `ramped` (`from`, `to`, `over: <ISO-8601>`) · `stepped` (`steps: [{rate, hold: <ISO-8601>}]`, holds final rate after the last step).

**`duration` kinds:** `time` (`value: <ISO-8601>`) · `count` (`value: <int>` ops) · `until_stop_condition` (paired with `stop_conditions[]`).

**`provisioning`:** `skip` (caches/tables must already exist — the norm when something else owns the schema) · `emit` (write cache XML / DDL to the output dir, don't apply) · `apply` (create absent caches/tables).

## data.yaml

```yaml
schema_version: 2
schemas:
  - name: customer            # maps 1:1 to a GG cache/table name (see gotcha)
    update_ratio: 0.05        # fraction of writes that update existing keys vs insert
    columns:
      - name: id
        key: true             # exactly one key column per schema
        null_rate: 0.0
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: first_name
        null_rate: 0.0
        value_source: { kind: datafaker, expression: "#{name.firstName}" }
  - name: account
    update_ratio: 0.0
    columns:
      - name: id
        key: true
        null_rate: 0.0
        value_source: { kind: sequence, start: 1, step: 1 }
      - name: customer_id
        affinity: true        # colocation key (GG8 affinityKey / GG9 COLOCATE BY)
        null_rate: 0.0         # parent-fk-ref columns must NOT have null_rate > 0
        value_source:
          kind: parent-fk-ref
          parent_schema: customer
          parent_column: id
          cohort_buckets:      # heavy-tail: 10% of parents get 100 children, 90% get 1
            - { share: 0.10, multiplier: 100 }
            - { share: 0.90, multiplier: 1 }
```

**`value_source` kinds** (authoritative list in the data JSONSchema): `sequence` · `datafaker` (DataFaker `expression`) · `unique` (unique-within-run) · `weighted-choice` (`choices: [{value, weight}]`) · `yaml-data` (`path`, `key` — static fixture list) · `parent-fk-ref` (FK relation + `cohort_buckets`) · `key-suffix` (`base_column`, `separator`, `length`).

## Gotchas (the ones that cost time)

1. **`transaction_scope: business_event` requires TRANSACTIONAL caches.** It wraps a root emission + its children in one transaction. GG8 8.9+ **rejects atomic-cache operations inside a transaction**, so against ATOMIC caches (the GG8 `CREATE TABLE` default) every write rolls back — shows up as a high error rate (`op_errors`, `TargetReportedFailure`). Use `transaction_scope: none` unless every target cache is TRANSACTIONAL. Source: `Gg8KvTarget`.
2. **Multi-pod rate is NOT divided across pods** (verified empirically 2026-06-15). With `distribution.replicas = N` and `rate.ops_per_second = R`, **each pod runs at R**, so total ≈ R × N. To hit a *total* target T across N pods, set `ops_per_second = ceil(T / N)`. (The code carries a "future work: divide rate across workers" intent — until that lands, treat rate as per-pod.)
3. **`partition_count >= replicas`** is required (`DistributionValidator`). Distribution uses a Coordinator (k8s Lease + ConfigMap leader election) and only activates when `POD_NAME`/`POD_NAMESPACE` are set (downward API); local runs fall back to single-pod.
4. **Schema name = cache name.** A `data.yaml` schema named `account` writes to a GG cache/table literally named `account` — **not** `SQL_PUBLIC_ACCOUNT`. If a consumer reads from SQL-created `SQL_PUBLIC_*` caches, generator load won't appear there unless the schema names and key/value shapes are aligned to those caches.
5. **Durations are ISO-8601** (`java.time.Duration`): `PT10M`, not `10m` (`DateTimeParseException`).
6. **Single-thread per pod.** One pod is bounded by GG round-trip latency, so raising `ops_per_second` alone plateaus — add pods (`replicas`) to push more total throughput.
7. **ops `schema_version: 6` needs a generator built at or after 2026-08-18.** v6 makes `histogram_highest_ms` and `histogram_significant_digits` required inside `metrics:`. An older archive refuses the file outright ("only supports up to schema_version 5"), and a newer generator refuses a v6 `metrics:` block that omits either key. `MigrateOpsV5toV6` fills both in automatically — but it rewrites the file through SnakeYAML and **drops every comment**, so a hand-commented `ops.yaml` should be hand-edited instead. `histogram_significant_digits` is capped at 4: cost is ~100x per extra digit, and 5 would mean ~21 MB/sec of allocation per instance.
8. **ops `schema_version: 7` invalidates every deployed generator archive.** v7 removes `targets:` and `scenario.target`; the cluster arrives as the required `--target-cluster` flag instead. The two directions both fail: a pre-v7 archive refuses a v7 file outright ("only supports up to schema_version 6"), and a v7 archive refuses to start without the flag. So upgrading an ops file is not a config-only change — **every** installed archive and image has to be rebuilt and redeployed alongside it, and any launcher that does not pass `--target-cluster` yet (a systemd unit, a k8s manifest, a script) has to be updated in the same pass. `MigrateOpsV6toV7` migrates the file automatically but rewrites it through SnakeYAML and **drops every comment**, so hand-edit a commented `ops.yaml` instead. It also **cannot** preserve the scenario→cluster wiring — nothing in v7 stores it — so it logs a WARN per scenario naming the cluster it discarded and the flag that now supplies it. Read those warnings before discarding the log; they are the only record of what your launch arguments should be.
9. **The `target` telemetry attribute now carries a cluster name, not a target alias.** The attribute *name* is unchanged (`target`), so existing queries keep resolving — but a dashboard that groups by it re-labels, showing cluster names where `targets[]` aliases used to appear. Panels keep working; saved queries filtering on a literal alias silently match nothing. This is the same class as the "metrics arrive, Prometheus is healthy, every panel is empty" failure: nothing errors, so only the graph tells you.

## Metrics

- **OTel instruments** (always recorded): in-flight, op duration histogram, errors, target rate, observed/achieved rate — tagged by scenario/target/schema/operation, where `target` is the **cluster name** from `--target-cluster` (see gotcha 9). Exported per the `otel:` block.
- **Live Kafka sink** (only when `metrics:` present): a JSON snapshot every `interval_ms` to the named topic. Fields (camelCase on the wire, see `metrics/MetricsSnapshot.kt`): `observedTps`, `avgLatencyMs`, `totalOps`, `errorCount`, `runAvgTps`, `runAvgLatencyMs`, `runLatencyHistogram`, `targetTps`, `runGroup`, `runId`, `active`. Consumers (e.g. a UI) subscribe for a live rate/latency feed without scraping Prometheus.
  - **`observedTps`/`avgLatencyMs` are interval figures; `runAvgTps`/`runAvgLatencyMs` are whole-run.** The first pair makes a live graph track current load; the second pair is what an end-of-run summary reports.
  - **`avgLatencyMs` is an interval *mean*, not a percentile.** No scalar percentiles are on this feed. What *is* on it is `runLatencyHistogram` — the instance's whole-run HdrHistogram, compressed encoding, base64, **microseconds** — which a consumer reads any percentile off. Sized by `histogram_highest_ms`/`histogram_significant_digits`; an operation slower than the ceiling is clamped to it, not dropped, so a p99 pinned at the ceiling reads as "slower than we can measure".
  - **Merge histograms, never average percentiles.** Percentiles do not compose: the max p90 across a fleet's instances is the worst instance's p90, not the fleet's. Decode each instance's histogram and `add()` them, then read the percentile off the merged result.
  - `scenario/LatencyHistogram.kt` is a *different*, unbounded histogram used only for stop conditions and exported nowhere — do not confuse the two. `errorCount` is cumulative, not a rate.
  - **`targetTps` tracks the current target**, so it follows a `ramped`/`stepped` schedule and any live override — not the run's start rate.
  - **Two ids.** `runId` is per *process*; each instance of a distributed run has its own, which is how a consumer counts live instances and expires a dead one. `runGroup` is shared by every instance launched together (`--run-group`) and is what a consumer aggregates and addresses by.
  - Each instance emits a final `active=false` snapshot on clean stop, carrying its whole-run figures and final histogram. A killed process emits nothing (there is no SIGTERM handler) — but because the histogram rides on *every* tick, the last tick received is still a usable summary. Consumers need their own staleness timeout regardless.
  - **Wire cost:** roughly 5–30 KB per instance per second at the recommended bounds, republished each tick because the histogram is cumulative. It grows with the spread of buckets touched and the variance in their counts, not with run length, so it plateaus. Well inside Kafka's 1 MB default `max.request.size`.

## Runtime control

Only when `control:` is present (v5+). The generator consumes `ControlCommand` JSON from the named topic and adjusts its rate **without restarting** — this is how a UI drives load up and down mid-run.

```json
{"runGroup": "20260809T101500Z", "targetTpsPerInstance": 250.0, "issuedAtMs": 1754731200000}
```

- **`targetTpsPerInstance` is per instance, not the fleet total.** An instance cannot know how many peers are live, so the sender divides. `0.0` pauses the instance — it stays connected and keeps reporting, so graphs flatline rather than vanish.
- An instance ignores commands whose `runGroup` is not its own, so one topic serves concurrent runs.
- Every instance subscribes under a **unique consumer group**, so the topic broadcasts: one command reaches the whole fleet.
- An override outranks the scenario's `rate:` schedule; the configured constant/ramp/step runs untouched until the first command arrives.
- All three required fields must be present. A payload missing `targetTpsPerInstance` is rejected rather than defaulted — Jackson would fill it with `0.0`, silently pausing the fleet.

## CLI

Launched via the generator's own CLI (entry points under `data-generator-gg8`/`-gg9`; dispatcher `ScenarioRunnerCli`). It takes the scenario name + paths to `ops.yaml`, `data.yaml`, the client-endpoints file, an output dir, **`--run-group <id>`** — the id shared by every instance of one logical run (see §Metrics) — and **`--target-cluster <name>` (required from ops v7)**, the cluster to write to, resolved against the client-endpoints file. All are required; a missing flag fails with a message naming it and the full expected invocation. **Verify the exact flag names against `ScenarioRunnerCli` / the `*Main` classes before scripting** — the config files above are the stable contract; launch flags are an implementation detail.

**Why the cluster is a flag and not a config field.** The same load shape is routinely run against different clusters, and a scenario that named one made the whole ops file specific to a single demo. As a flag it is also checked before any load is generated: each `*Main` resolves the cluster up front and fails with a `MisconfigurationException` naming `--target-cluster` if the name is unknown or belongs to the other GridGain major version. That eager check matters — the resolution failure surfaces from inside the write path, where it is counted as an op error and discarded, so without it an unresolvable cluster produces a full-length run reporting zero successes and **exit code 0**.

## Sources of truth (verify here when exact)

This repo's own `CLAUDE.md` (§Key files) is the canonical file map — defer to it. References below
are package-relative (the repo is multi-module: `-core`, `-gg8`, `-gg9`).
- version constants: `config/ConfiguredVersions.kt` (`CURRENT_OPS_SCHEMA_VERSION`, `CURRENT_DATA_SCHEMA_VERSION`)
- migrations: the ops/data migration runner(s) + `Migrate*` step classes under `config/` (defer to the repo CLAUDE.md §Key files for exact filenames — both `ConfigMigration` and `OpsConfigMigrationRunner`/`DataConfigMigrationRunner` naming have appeared)
- ops/data JSONSchema: `src/main/resources/schema/{ops,data}/` (per version)
- rate limiters: `scenario/` (Constant/Ramped/Stepped, plus `ControllableRateLimiter` wrapping them for runtime override)
- runtime control: `control/` (`ControlCommand`, `ControlListener`, `KafkaControlListener`)
- transaction/atomicity rule: `target/Gg8KvTarget.kt` (gg8 module)
- distribution/coordinator: `coordination/` package + the distribution validator
- metrics: `metrics/` — `MetricsSnapshot`, `LiveMetricsReporter`, `KafkaMetricsSink`, `LatencyHistogramBounds`, `HistogramCodec`. OTel instruments (a separate, always-on export) are under `observability/`.
- CLI: `cli/` package (`CliArgs` for the flag set, `ScenarioRunnerCli` dispatcher + the `*Main` entry points, which is where the target cluster is resolved)
- target cluster: `config/TargetSpec.kt` — a sealed hierarchy still, but **no longer deserialized** from ops.yaml since v7; each `*Main` constructs the one variant it serves from `--target-cluster`

## Maintenance

This skill documents a moving target. **When you change the generator's config surface** — an ops/data schema field, a rate/value-source kind, `transaction_scope`/distribution semantics, the metrics or control block, or the CLI — **update this file in the same change and bump the *Last updated* date.** Prefer citing a source file over duplicating volatile detail. This rule is also recorded in this repo's `CLAUDE.md`.
