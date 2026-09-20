---
name: gridgain-demo-data-generator
description: How to USE the GridGain demo data generator — authoring ops.yaml/data.yaml, choosing rate kinds, transaction_scope, distribution (multi-pod), provisioning, live metrics, runtime rate control, and stopping a run cleanly. Use when configuring or running a data-generator scenario, debugging generator throughput/errors, driving load up/down at runtime, running a scenario until an operator stops it, deciding how to load a GridGain cluster, or editing ops.yaml/data.yaml. Standalone component — it has no dependency on the gradle plugin or any demo.
---

# GridGain Demo Data Generator — Usage

*Last updated: 2026-09-20*

A YAML-configured streaming data generator for GridGain 8/9 clusters. It is a **standalone** component (consumed by the plugin and the demo UI, but depends on neither). This skill is the usage contract: the config surface and the semantics that bite. It does **not** describe how any particular consumer launches it — for the gradle plugin's `dataGenerate` dispatch, see the `gridgain-demo-toolkit` skill.

> **Verify before asserting.** Flags, enum values and version numbers drift. The config-file *shape* below is the stable contract; cite the source files in §Sources when a fact must be exact, and re-check against them. Keep the *Last updated* date current when you change this file.

## Two config files

| File | Purpose | Current schema_version |
|------|---------|------------------------|
| `ops.yaml` | scenarios (rate/duration/scope/concurrency), metrics, control, otel | **9** |
| `data.yaml` | schemas → columns → value sources, FK relations, **replication** | **3** |

Both carry a `schema_version` and are **auto-migrated** forward before validation (`OpsConfigMigrationRunner`, `DataConfigMigrationRunner`). Validation failures are fatal with remediation text.

## ops.yaml

```yaml
schema_version: 9
metrics:                       # optional — live throughput/latency to Kafka (§Metrics)
  broker:                            # v9 — a reference, not an address (§Broker references)
    kind: element
    name: payments-bus               # a `message_brokers` entry in the toolkit's demo-config.yaml
  topic: "datagen-metrics"
  interval_ms: 1000
  histogram_highest_ms: 60000        # required from v6 — latency histogram ceiling
  histogram_significant_digits: 3    # required from v6 — HdrHistogram precision (1-4)
control:                       # optional (v5+) — commands in: set_rate, stop (§Runtime control)
  broker:                            # or `kind: address` for a broker this demo does not own
    kind: address
    bootstrap_servers: "kafka:9092"
  topic: "datagen-control"
otel:                          # optional — OpenTelemetry export (exporter: none|otlp|prometheus)
  exporter: otlp
  endpoint: http://collector:4317
scenarios:
  - name: load
    root_schemas: [customer]   # emitted with their transitive children (parent-fk-ref)
    rate: { kind: constant, ops_per_second: 1000 }
    duration: { kind: time, value: "PT10M" }   # ISO-8601 Duration (see gotcha)
    concurrency: 32            # required from v8 — worker threads in THIS process (§Concurrency)
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

**`stop_conditions` kinds** (optional list, ORed, evaluated after each op): `latency_p99_above` / `latency_p999_above` (`threshold: <ISO-8601>`) · `error_rate_above` (`threshold: 0.0–1.0`) · `external_signal` (no fields). A triggered stop is the run's outcome, not a crash — it lands in `result.yaml`'s `stop_reason`.

**`external_signal` is what makes "run until stopped" real.** It declares that the scenario is *intentionally* unbounded and ends when an operator says so, delivered as a `stop` command on the control channel (§Runtime control):

```yaml
control:
  broker: { kind: element, name: payments-bus }
  topic: "datagen-control"
scenarios:
  - name: run-until-stopped
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 1000 }
    duration: { kind: until_stop_condition }
    concurrency: 32
    stop_conditions:
      - { kind: external_signal }
    read_ratio: 0.0
```

- **It requires a top-level `control:` block.** Without one nothing can raise the signal, so the run would be unbounded and unstoppable short of a SIGTERM. `ExternalSignalControlValidator` fails the parse naming the scenario and the block to add.
- **`external_signal` also lifts the internal safety cap** on `until_stop_condition`, which otherwise bounds such a run at one minute. Declaring the condition is the opt-in to a genuinely unbounded run; nothing else lifts the cap.
- The threshold conditions only start judging after 100 operations (too few samples make a percentile meaningless). `external_signal` is checked *ahead* of that gate — an operator pressing stop is not a statistic, and at a low rate the 100th op could be minutes away.
- `until_stop_condition` with **no** `stop_conditions` at all is not rejected — nothing rejected it before either — but it now logs a config warning, because nothing decides when such a run ends and it just runs into the one-minute cap.

**`provisioning`:** `skip` (caches/tables must already exist — the norm when something else owns the schema) · `emit` (write cache XML / DDL to the output dir, don't apply) · `apply` (create absent caches/tables).

## Concurrency (v8+)

`concurrency` is how many **worker threads one generator process runs**. It is required from v8; `MigrateOpsV7toV8` writes `1` into every migrated scenario, so an upgraded file behaves exactly as it did before.

**This is the setting that decides whether you can load a cluster at all.** Before v8 a run was one JVM with a single emitting thread, so its ceiling was the reciprocal of the target's round-trip latency, *not* whatever `rate` asked for — against a 200 µs round trip one thread tops out near 5,000 ops/s and a `rate` of 1,000,000 simply never throttles. The only way to add load was to launch more processes. With `concurrency: N` the process holds N operations in flight.

```yaml
scenarios:
  - name: max-write
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 1000000 }   # effectively unlimited
    duration: { kind: until_stop_condition }
    concurrency: 64
    stop_conditions: [{ kind: external_signal }]
    read_ratio: 0.0
```

- **`rate` stays a per-process target shared by the workers, not per thread.** `concurrency: 64` with `ops_per_second: 1000` is still 1000 ops/s for the process; the threads take turns. Raise `rate` too, or it becomes the bottleneck instead of the cluster.
- **`set_rate`'s `targetTpsPerInstance` likewise addresses the whole process**, not each thread.
- **Every process in a fleet must use the same `concurrency`.** Each worker takes a slice of the key space computed from it (`workerStripe`), so a 1-thread and a 4-thread process in one fleet produce stripes of different widths that **overlap and write duplicate primary keys**. Uniformity holds automatically when all instances launch from the same ops.yaml — take care if you ever hand-edit one.
- It composes with `--instance-index`/`--instance-count` and with Kubernetes distributed mode: worker `w` of process stripe `(i, n)` owns stripe `(i*C + w, n*C)`, so M machines × C threads tile the key space exactly.
- **Sizing:** start at roughly the number of vCPUs on the client machine and raise it until server CPU stops climbing. Client threads are mostly blocked on the network, so a client can usefully run many more threads than it has cores; the right value is the one where the *cluster* saturates before the client does.
- `concurrency: 1` is not merely equivalent to the old behaviour, it *is* the old behaviour: the runner spawns no threads and runs the loop on the calling thread.

## Broker references (v9+)

`metrics:` and `control:` no longer carry a `kafka_bootstrap` address. Each takes a `broker:` with
a `kind` discriminator, and the two forms are **disjoint configurations, not a value with a
fallback** — the document says which it means and the generator never guesses:

| form | when | resolved |
|---|---|---|
| `{ kind: element, name: <n> }` | the demo toolkit deployed the broker | at launch, from `broker-endpoints.yaml` (`--broker-endpoints`) |
| `{ kind: address, bootstrap_servers: <host:port> }` | a broker this demo does **not** own — a laptop, a lab broker, a managed service | used verbatim; needs no endpoints file |

**Why.** A v8 address was a hand-copied duplicate of something the toolkit already knew, and it
went stale silently when a broker was redeployed elsewhere. A name is resolved per run, so there
is one definition of where the broker is.

**The address form is load-bearing, not symmetry.** The generator must not depend on the plugin;
without it a standalone run could not use `metrics:`/`control:` at all.

**Resolution is eager**, in `ScenarioRunnerCli.resolve()` — before the target is connected and
before any row is written. A named broker that is configured but **not deployed** does not appear
in `broker-endpoints.yaml`, so the run fails at launch naming the broker, what the file does
declare, and both remedies. It never silently disables the channel, which would look exactly like
a working demo that happens to report nothing.

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
6. **~~Single-thread per pod.~~ Fixed in v8 — set `concurrency`.** Until v8 one process ran one emitting thread, so it was bounded by GG round-trip latency and raising `ops_per_second` alone plateaued; the only remedy was more processes or pods. A scenario's `concurrency` (§Concurrency) now sets worker threads per process, which is the first knob to reach for when a run will not load the cluster. Raise `rate` alongside it — the workers share one per-process rate, so an unchanged `rate` just becomes the new bottleneck.
7. **ops `schema_version: 6` needs a generator built at or after 2026-08-18.** v6 makes `histogram_highest_ms` and `histogram_significant_digits` required inside `metrics:`. An older archive refuses the file outright ("only supports up to schema_version 5"), and a newer generator refuses a v6 `metrics:` block that omits either key. `MigrateOpsV5toV6` fills both in automatically — but it rewrites the file through SnakeYAML and **drops every comment**, so a hand-commented `ops.yaml` should be hand-edited instead. `histogram_significant_digits` is capped at 4: cost is ~100x per extra digit, and 5 would mean ~21 MB/sec of allocation per instance.
8. **ops `schema_version: 7` invalidates every deployed generator archive.** v7 removes `targets:` and `scenario.target`; the cluster arrives as the required `--target-cluster` flag instead. The two directions both fail: a pre-v7 archive refuses a v7 file outright ("only supports up to schema_version 6"), and a v7 archive refuses to start without the flag. So upgrading an ops file is not a config-only change — **every** installed archive and image has to be rebuilt and redeployed alongside it, and any launcher that does not pass `--target-cluster` yet (a systemd unit, a k8s manifest, a script) has to be updated in the same pass. `MigrateOpsV6toV7` migrates the file automatically but rewrites it through SnakeYAML and **drops every comment**, so hand-edit a commented `ops.yaml` instead. It also **cannot** preserve the scenario→cluster wiring — nothing in v7 stores it — so it logs a WARN per scenario naming the cluster it discarded and the flag that now supplies it. Read those warnings before discarding the log; they are the only record of what your launch arguments should be.
9. **SIGTERM is now a clean stop, inside a 10-second budget.** A JVM shutdown hook (registered in `ScenarioRunnerCli.run`, so both `Gg8Main` and `Gg9Main` get it) raises the run's stop signal, lets the **in-flight operation finish**, and then runs the normal end-of-run path: final `active=false` metrics snapshot with whole-run figures and the merged HdrHistogram, target close, `result.yaml`, `state.yaml`. The hook waits up to **10s** for that (`ScenarioRunnerCli.GRACEFUL_STOP_SECONDS`) before letting the JVM halt — a JVM in shutdown halts the moment its hooks return and does *not* wait for other threads, so the wait is what stops the process beating its own cleanup. 10s is sized to sit well inside both deadlines that kill the process: a pod's `terminationGracePeriodSeconds` (**30s**, the k8s default — the generator's Deployment does not set it) and the host systemd unit's `TimeoutStopSec` (from `host_timeouts.unit_active`, 120s in the shipped template). Overrun that and you get SIGKILL and no final snapshot, i.e. the old behaviour. `stop_reason` reads `stopped by signal: <why>`, distinct from `count reached` / `time elapsed` / `until_stop_condition cap reached` and from a triggered stop condition. Note a **SIGKILL still emits nothing** — the per-tick histogram (§Metrics) remains the fallback.
10. **The control message gained a required `kind`, and the break is silent in both directions.** No ops schema version moves for this (`external_signal` and `control:`'s shape were already in ops v7), so **nothing in the config files tells you the fleet is out of date** — this is a *code* break with no version handshake behind it. A pre-`kind` archive accepts a new `set_rate` (it ignores the unknown `kind` field and finds the three fields it wants) but rejects a `stop` as "missing required field `targetTpsPerInstance`"; a new archive rejects the old undiscriminated payload outright. Either way the failure is a log line on the generator side, so from the sender's seat the rate slider works and the stop button does nothing. **Redeploy every archive and image in the same pass as the sender.**
11. **N processes sharing one `data.yaml` with a `sequence` source all write the same keys.** A `value_source: {kind: sequence, start: 1, step: 1}` starts at `start` in *every* process, so M concurrently launched generators contend on the same entries and the same partitions instead of dividing the key space. **Kubernetes distributed mode does this for you; host and local mode do not** — `Coordinator` derives a per-pod stripe but only activates in-cluster (it needs `POD_NAME`/`POD_NAMESPACE`), so every host and local run got the null stripe. Pass **`--instance-index i` / `--instance-count n`** (§CLI) — index unique per process, `0..n-1` — and each process strides by `n * step` from `start + i * step`, disjoint keys whose union is the single-process sequence.
    - **The symptom is not an error.** Measured on real hardware: 32 processes against a 2-node GG8 cluster settled at **93 ops/s, 498 ms mean latency, zero errors**, while both hosts' CPU was idle, server thread pools sat `active=0, qSize=0`, heap was fine and disk was 5% used. The tell is the data volume: **50M+ operations left 383 MB** in an 8 GB data region, because the writes were overwriting each other. So the diagnostic is "no errors, idle CPU on both sides, and far less data than ops" — nothing in the run's own output, or in the cluster's, reports it.
    - The flags are **optional and both-or-neither**; one without the other is refused naming both. Nothing validates that a fleet used each index exactly once — two processes given the same index write the same keys, which is the original failure with extra steps.
    - Supplying them **in Kubernetes distributed mode is refused**, not merged: the coordinator has already partitioned the key space and two sources of truth for it is the same class of defect. Drop the flags there.
12. **The `target` telemetry attribute now carries a cluster name, not a target alias.** The attribute *name* is unchanged (`target`), so existing queries keep resolving — but a dashboard that groups by it re-labels, showing cluster names where `targets[]` aliases used to appear. Panels keep working; saved queries filtering on a literal alias silently match nothing. This is the same class as the "metrics arrive, Prometheus is healthy, every panel is empty" failure: nothing errors, so only the graph tells you.
13. **ops `schema_version: 8` invalidates every deployed generator archive, like v7 did.** v8 makes `concurrency` required on every scenario. A pre-v8 archive refuses a v8 file outright ("only supports up to schema_version 7"), so **every installed archive and image must be rebuilt and redeployed in the same pass** as the ops file. `MigrateOpsV7toV8` fills `concurrency: 1` automatically but rewrites the file through SnakeYAML and **drops every comment** — hand-edit a commented `ops.yaml` instead. Unlike v6→v7 nothing is lost, so this migration reports nothing.
14. **Mixed `concurrency` across a fleet writes duplicate primary keys.** Each worker's key-space stripe is derived from `concurrency`, so processes that disagree on it produce stripes of different widths that overlap. Symptom is silent overwrites, not an error. Launch every instance of a run from the same ops.yaml.

15. **ops `schema_version: 9` invalidates every deployed generator archive, like v7 and v8 did.** v9 replaces `metrics.kafka_bootstrap` / `control.kafka_bootstrap` with a required `broker:` reference (§Broker references). A pre-v9 archive refuses a v9 file outright ("only supports up to schema_version 8"), so **every installed archive and image must be rebuilt and redeployed in the same pass** as the ops file. ⚠️ **Unlike 7→8, hand-bumping `schema_version` is NOT equivalent to running the migration.** `MigrateOpsV8toV9` performs a real rewrite — `kafka_bootstrap: X` → `broker: { kind: address, bootstrap_servers: X }` — so a hand-bumped file also needs those two keys rewritten by hand, or the v9 JSONSchema rejects it for a missing `broker`. (Hand-editing is still the right move for a heavily-commented ops.yaml: the migration rewrites through SnakeYAML and drops every comment.) The migration deliberately **never invents an element name** from an address — only a deployment knows that `10.0.0.5:9092` is `payments-bus` — so switching a channel to the reference form is always a manual, considered edit.


## Metrics

- **OTel instruments** (always recorded): in-flight, op duration histogram, errors, target rate, observed/achieved rate — tagged by scenario/target/schema/operation, where `target` is the **cluster name** from `--target-cluster` (see gotcha 12). Exported per the `otel:` block.
- **Live Kafka sink** (only when `metrics:` present): a JSON snapshot every `interval_ms` to the named topic. Fields (camelCase on the wire, see `metrics/MetricsSnapshot.kt`): `observedTps`, `avgLatencyMs`, `totalOps`, `errorCount`, `runAvgTps`, `runAvgLatencyMs`, `runLatencyHistogram`, `targetTps`, `runGroup`, `runId`, `active`. Consumers (e.g. a UI) subscribe for a live rate/latency feed without scraping Prometheus.
  - **`observedTps`/`avgLatencyMs` are interval figures; `runAvgTps`/`runAvgLatencyMs` are whole-run.** The first pair makes a live graph track current load; the second pair is what an end-of-run summary reports.
  - **`avgLatencyMs` is an interval *mean*, not a percentile.** No scalar percentiles are on this feed. What *is* on it is `runLatencyHistogram` — the instance's whole-run HdrHistogram, compressed encoding, base64, **microseconds** — which a consumer reads any percentile off. Sized by `histogram_highest_ms`/`histogram_significant_digits`; an operation slower than the ceiling is clamped to it, not dropped, so a p99 pinned at the ceiling reads as "slower than we can measure".
  - **Merge histograms, never average percentiles.** Percentiles do not compose: the max p90 across a fleet's instances is the worst instance's p90, not the fleet's. Decode each instance's histogram and `add()` them, then read the percentile off the merged result.
  - `scenario/LatencyHistogram.kt` is a *different* histogram, used only for stop conditions and exported nowhere — do not confuse the two. It was unbounded until v8 (it retained every sample, ~1.4 GB/hour at 50k ops/s, and worsened with rate); it is now an HdrHistogram and flat in memory. `errorCount` is cumulative, not a rate.
  - **`targetTps` tracks the current target**, so it follows a `ramped`/`stepped` schedule and any live override — not the run's start rate.
  - **Two ids.** `runId` is per *process*; each instance of a distributed run has its own, which is how a consumer counts live instances and expires a dead one. `runGroup` is shared by every instance launched together (`--run-group`) and is what a consumer aggregates and addresses by. **A launcher should supply the per-process one** (`--instance-id`, §CLI): self-minted, it is a string that appears nowhere the launcher can see, so "which instance is slow" has no answer.
  - **`shape`** (added with `--instance-id`) carries what this process is *able* to do: `concurrency`, and the `stripe` (`index`, `count`) it owns, or null when it owns the whole key space. Nested rather than two flat ints deliberately — a missing reference type fails deserialization, while a missing JVM primitive is silently filled with 0. A consumer sums `concurrency` across live instances for the fleet's in-flight operations (the only honest input to a throughput ceiling), and checks that the stripes *tile*: same `count` everywhere, indices exactly `0 until count`. A fleet whose slices overlap writes duplicate keys and every throughput figure it reports is meaningless.
  - Each instance emits a final `active=false` snapshot on clean stop, carrying its whole-run figures and final histogram. **SIGTERM is a clean stop** (gotcha 9), so a `dataGeneratorTeardown`, a deleted pod, a `systemctl stop` and an operator `stop` command all produce that snapshot — a consumer no longer has to supply run-summary figures *into* teardown, it can read them off the run's own last snapshot. A **SIGKILL** still emits nothing (grace period overrun, lost node, `kill -9`) — but because the histogram rides on *every* tick, the last tick received is still a usable summary. Consumers need their own staleness timeout regardless.
  - **Wire cost:** roughly 5–30 KB per instance per second at the recommended bounds, republished each tick because the histogram is cumulative. It grows with the spread of buckets touched and the variance in their counts, not with run length, so it plateaus. Well inside Kafka's 1 MB default `max.request.size`.

## Runtime control

Only when `control:` is present (v5+). The generator consumes `ControlCommand` JSON from the named topic and acts on it **without restarting** — this is how a UI drives load up and down, and stops a run, mid-flight. **Two kinds**, discriminated by a required `kind` field:

```json
{"kind": "set_rate", "runGroup": "20260809T101500Z", "targetTpsPerInstance": 250.0, "issuedAtMs": 1754731200000}
```

```json
{"kind": "stop", "runGroup": "20260809T101500Z", "issuedAtMs": 1754731200000}
```

- **`kind` is required, and there is no compatibility with the old flat message.** The pre-`kind` payload (`{runGroup, targetTpsPerInstance, issuedAtMs}` with no discriminator) is now rejected and logged, as is any unrecognised kind. **Every deployed generator archive and image must be redeployed** in the same pass as any sender that starts emitting the new shape — an old archive silently ignores `kind` (`set_rate` looks like an unknown field to it, so it reads as a rate command and still works) while a new archive silently ignores the old flat message, so a half-upgraded fleet responds to load commands but not to stop.
- **`set_rate`'s `targetTpsPerInstance` is per instance, not the fleet total.** An instance cannot know how many peers are live, so the sender divides. `0.0` pauses the instance — it stays connected and keeps reporting, so graphs flatline rather than vanish. A `0.0`-paused instance is **still stoppable**: a `stop` releases the paused pacing loop.
- **`stop` ends any run**, not only one declaring `external_signal` — it is an operator instruction, and refusing it for a `time`/`count` run would be surprising. It raises exactly the signal a SIGTERM does, so the instance finishes its in-flight operation and then emits its final `active=false` snapshot, closes its target and writes a real `result.yaml` (gotcha 9). A scenario declaring `external_signal` reports `stop_reason: external_signal raised: …`; any other run reports `stopped by signal: …`.
- An instance ignores commands whose `runGroup` is not its own, so one topic serves concurrent runs — including a `stop`, which never crosses runs.
- Every instance subscribes under a **unique consumer group**, so the topic broadcasts: one command reaches the whole fleet.
- A rate override outranks the scenario's `rate:` schedule; the configured constant/ramp/step runs untouched until the first command arrives.
- **Per-kind required fields, all checked before deserializing.** `set_rate` needs `runGroup`, `targetTpsPerInstance`, `issuedAtMs`; `stop` needs `runGroup`, `issuedAtMs`. A `set_rate` missing its rate is rejected rather than defaulted — Jackson would fill the `Double` with `0.0`, silently pausing the fleet.
- Nothing here is fatal: a malformed, undiscriminated, mis-addressed or unusable command is logged and skipped. Losing control must never take down a running load test.

## CLI

Launched via the generator's own CLI (entry points under `data-generator-gg8`/`-gg9`; dispatcher `ScenarioRunnerCli`). It takes the scenario name + paths to `ops.yaml`, `data.yaml`, the client-endpoints file, an output dir, **`--run-group <id>`** — the id shared by every instance of one logical run (see §Metrics) — and **`--target-cluster <name>` (required from ops v7)**, the cluster to write to, resolved against the client-endpoints file. All of those are required; a missing flag fails with a message naming it and the full expected invocation. **Verify the exact flag names against `ScenarioRunnerCli` / the `*Main` classes before scripting** — the config files above are the stable contract; launch flags are an implementation detail.

**Optional flags:**
- **`--broker-endpoints <path>`** — the `broker-endpoints.yaml` the demo toolkit writes when it deploys a `message_brokers` element (under `<demoOutputDirectory>/client/`). Needed only when a `metrics:`/`control:` block uses `broker: { kind: element, ... }`; a run using literal addresses, or one with no such blocks, does not need it. Deliberately **not required**: most demos have no message broker, and requiring it would break every existing invocation. The toolkit passes it automatically when the file exists.
- **`--otel-endpoint-override <url>`** — wins over `ops.otel.endpoint`, and promotes `exporter: NONE` to `OTLP` (passing an override only makes sense if exporting is wanted).
- **`--instance-id <id>`** — **this process's id, supplied instead of minted.** Absent → `RunId.generate()`, as before. It is the `runId` on every metrics snapshot (see §Metrics, "Two ids"), so whoever launched the process can find it again: the toolkit passes the systemd instance name on hosts and `$(POD_NAME)` on Kubernetes, and pairs it with `service.instance.id` in `OTEL_RESOURCE_ATTRIBUTES` so the Grafana series, the launcher's own record and the metrics feed are one string. Without it a launcher knew a process by one id and the process published another, and nothing joined them. A blank value is treated as absent rather than as an empty id.
- **`--instance-index <i> --instance-count <n>`** — **the key-space stripe for a multi-process run.** `n` is how many generator processes share the key space; `i` is the 0-based slice this one owns, and every `sequence` value source then strides by `n * step` from `start + i * step`. **Both or neither**, validated together: one without the other is refused naming both, as are a non-integer value, a count below 1, and an index outside `0..n-1`. Absent → this process owns the whole key space, exactly today's behaviour. **Refused** when the coordinator has already supplied a stripe (Kubernetes distributed mode) rather than one silently winning. **A multi-process host or local load test needs these** — see gotcha 11 for what happens without them, and note nothing checks that a fleet used each index exactly once.

**Why the cluster is a flag and not a config field.** The same load shape is routinely run against different clusters, and a scenario that named one made the whole ops file specific to a single demo. As a flag it is also checked before any load is generated: each `*Main` resolves the cluster up front and fails with a `MisconfigurationException` naming `--target-cluster` if the name is unknown or belongs to the other GridGain major version. That eager check matters — the resolution failure surfaces from inside the write path, where it is counted as an op error and discarded, so without it an unresolvable cluster produces a full-length run reporting zero successes and **exit code 0**.

## Replication (`backups`, `write_synchronization_mode`) — data v3

Per schema, and **only applied when the generator creates the cache** (`provisioning: emit|apply`):

```yaml
schemas:
  - name: customer
    backups: 1                            # copies beyond the primary; 0 = one copy, no redundancy
    write_synchronization_mode: full_sync # primary_sync | full_sync | full_async
```

- **`backups` alone does not make a put wait for its replica.** The default `primary_sync` returns
  as soon as the primary holds the row, so replication happens behind the call and none of its cost
  appears in the latency on the dashboard. `full_sync` is what makes the measurement mean what a
  reader assumes.
- **Reads stay local.** `readFromBackup` is GridGain's default (true) and is not exposed here: with
  `backups: 1` on two nodes every node holds every partition, so a read is answered wherever it
  lands. Measured on the Power lab: `put` 0.603 ms, `get` 0.229 ms — the asymmetry is the point.
- ⚠️ **A cache cannot be re-configured in place.** GG8 rejects `getOrCreateCache` against an
  existing cache with a different `backups`/`writeSynchronizationMode`/atomicity, so changing
  either value means **destroying the cache first**
  (`control.sh --host <bind-addr> --port 11211 --cache destroy --caches <name> --yes`; note
  `--host`, the connector binds the machine address, not loopback). `MigrateV2toV3` writes
  `backups: 0` / `primary_sync` into every pre-v3 schema, so upgrading changes nothing by itself.

## Throughput: resolve cache handles once

`Gg8KvTarget` used to call `IgniteClient.getOrCreateCache(name)` inside every `putRow` and every
`read`. On a **thin client that is a remote cache-lifecycle round trip**, not a local lookup, and
it sat inside the section each operation times and reports as its latency. Fixed 2026-09-20
(`CacheHandles`, one handle per schema, cleared when the client closes).

Measured on two Power11 LPARs, 2 generator processes, before -> after:

| threads/process | before | after |
|---|---|---|
| 8 | 51,301 ops/s @ 0.302 ms | 73,834 @ 0.198 ms |
| 16 | **70 @ 455 ms** | 122,481 @ 0.217 ms |
| 32 | **87 @ 732 ms** | 153,848 @ 0.259 ms |

Two lessons worth keeping. **Concurrency did not scale at all before it** — past ~16 operations in
flight throughput collapsed ~1,700x with both machines and the network idle, which reads exactly
like a cluster or network fault and is neither. And a standalone probe doing per-op
`getOrCreateCache` against a *single* cache showed only a 40% tax, not the collapse: the collapse
needs the generator's real shape, several schemas and several rows per event. **Do not model this
workload with one cache and one operation per iteration.**

⚠️ **Affinity awareness is still off.** In `ignite-core` 8.9.18 the setter is
`ClientConfiguration.setAffinityAwarenessEnabled(boolean)` (not `setPartitionAwarenessEnabled`,
which does not exist there) and it defaults to false, so every request from a process goes to one
node over one connection and the rest of the cluster is idle. Routing is the GridGain client's
job, not the generator's — this is one flag.

## Sources of truth (verify here when exact)

This repo's own `CLAUDE.md` (§Key files) is the canonical file map — defer to it. References below
are package-relative (the repo is multi-module: `-core`, `-gg8`, `-gg9`).
- version constants: `config/ConfiguredVersions.kt` (`CURRENT_OPS_SCHEMA_VERSION`, `CURRENT_DATA_SCHEMA_VERSION`)
- migrations: the ops/data migration runner(s) + `Migrate*` step classes under `config/` (defer to the repo CLAUDE.md §Key files for exact filenames — both `ConfigMigration` and `OpsConfigMigrationRunner`/`DataConfigMigrationRunner` naming have appeared)
- ops/data JSONSchema: `src/main/resources/schema/{ops,data}/` (per version)
- rate limiters: `scenario/` (Constant/Ramped/Stepped, plus `ControllableRateLimiter` wrapping them for runtime override)
- graceful stop: `scenario/StopSignal.kt` (the one signal every stop source raises) + the shutdown hook and `GRACEFUL_STOP_SECONDS` in `cli/ScenarioRunnerCli.kt`
- runtime control: `control/` (`ControlCommand` — the sealed `set_rate`/`stop` hierarchy and its per-kind required fields — plus `ControlListener`, `KafkaControlListener`)
- stop conditions: `scenario/StopConditionEvaluator.kt`; the `external_signal`/`control:` rule is `ExternalSignalControlValidator` in `config/CrossElementValidator.kt`
- transaction/atomicity rule: `target/Gg8KvTarget.kt` (gg8 module)
- distribution/coordinator: `coordination/` package + the distribution validator
- metrics: `metrics/` — `MetricsSnapshot`, `LiveMetricsReporter`, `KafkaMetricsSink`, `LatencyHistogramBounds`, `HistogramCodec`. OTel instruments (a separate, always-on export) are under `observability/`.
- CLI: `cli/` package (`CliArgs` for the flag set and the `--instance-index`/`--instance-count` validation, `ScenarioRunnerCli` dispatcher + the `*Main` entry points, which is where the target cluster is resolved)
- key-space striping: `generation/SequenceValueSource.kt` (`PartitionStripe` + the stride arithmetic), `generation/ValueSourceFactory.kt` (where it reaches each sequence), `ScenarioRunnerCli.resolvePartitionStripe` (the coordinator-vs-flags precedence, which refuses both)
- broker references: `config/BrokerRef.kt` (the sealed `element`/`address` pair), `brokers/BrokerDirectory.kt` (`BrokerEndpointsSource`, the reader, `EXPECTED_SCHEMA_VERSION` — a deliberate duplicate of the plugin's writer constant), `config/MigrateOpsV8toV9.kt`, `schema/ops/v9.schema.json` (`$defs.broker_ref`)
- concurrency: `config/ScenarioSpec.kt` (`concurrency`), `generation/WorkerGenerators.kt` (per-worker generators + the max-cursor state merge), `generation/SequenceValueSource.kt` (`workerStripe` composition), `scenario/ScenarioRunner.kt` (`workerLoop`, first-stop-wins)
- target cluster: `config/TargetSpec.kt` — a sealed hierarchy still, but **no longer deserialized** from ops.yaml since v7; each `*Main` constructs the one variant it serves from `--target-cluster`

## Maintenance

This skill documents a moving target. **When you change the generator's config surface** — an ops/data schema field, a rate/value-source kind, `transaction_scope`/distribution semantics, the metrics or control block, or the CLI — **update this file in the same change and bump the *Last updated* date.** Prefer citing a source file over duplicating volatile detail. This rule is also recorded in this repo's `CLAUDE.md`.
