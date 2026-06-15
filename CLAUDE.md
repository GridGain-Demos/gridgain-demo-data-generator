# Data Generator — Capabilities Specification

## Context

The `gridgain-demo-data-generator` supplies streaming data to GridGain v8 and v9
clusters for benchmarking, proof-of-concept work, and demos. It exists alongside
the `gridgain-demo-gradle-plugin` (its primary consumer) and `gridgain-demo-ui`
(secondary consumer). This document replaces the prose in the project's
`CLAUDE.md` first-pass with a capabilities-oriented plan, distinguishing what is
in scope for the initial implementation, what is designed but deferred, and what
is left to future work.

The generator is yaml-configured — end users do not write Java or Kotlin. It
runs in a single process today, with a coordinator-and-workers shape designed in
so that horizontal scaling can be added later without restructuring core types.
Connection plumbing is consumed from `gridgain-demo-client-utils`; no parallel
implementation.

Each section below carries a **scope** tag:
- **In scope** — implemented in the first cut.
- **Designed, deferred** — abstractions and contracts must accommodate it; no
  code yet.
- **Future** — acknowledged direction; not designed in this document.

---

## §1 Data Shape *(in scope)*

A **schema** maps 1:1 to a GG8 cache or a GG9 table and is an ordered list of
**columns**.

- Each column binds to a **value source**: a stock DataFaker provider, a
  sequence, a unique-value source, the yaml-data provider, or a built-in
  extension provider.
- **Built-in extension providers** ship with the generator so end users write
  no code: `sequence`, `weighted-choice`, `parent-fk-ref`, `key-suffix`. Cohort
  sampling lives in the generator. DataFaker's own weighted selection is
  documented as POC-stage and is not relied upon.
- **Relations** are inline, foreign-key-shaped. A child schema's column is
  typed `parent-fk-ref` and names the parent schema and column. Cohort buckets
  attach to that column to govern how many children each parent gets.
- **Cohort buckets** are explicit `share` × `multiplier` pairs, e.g.
  `[{share: 0.01, multiplier: 1000}, {share: 0.10, multiplier: 50},
    {share: 0.89, multiplier: 1}]`.
  The model is fractal: relations chain (Customer → Order → OrderItem) and each
  level declares its own buckets.
- **Affinity.** A column annotation `affinity: true` marks the colocation key
  for its schema. The generator translates this to GG8's `affinityKey` cache
  config and GG9's `COLOCATE BY` clause at provisioning time.
- **Null rate.** A simple column may declare `null_rate: 0.0–1.0`
  (default `0.0`). The generator applies the rate after value generation.
  `null_rate` is invalid on `parent-fk-ref` columns; cross-element validation
  rejects this with a remediation message.
- **Per-schema `update_ratio`** declares the fraction of writes targeting an
  already-emitted key. KV mode resolves both insert and update to `put()`; the
  ratio drives the key-selection algorithm so warm-cache and update-path
  behavior can be modeled distinctly.

---

## §2 Scenario Engine *(in scope)*

A **scenario** is the unit of test execution. `ops.yaml` declares one or more.
Initial scope runs one scenario per invocation; selectable runs over multiple
scenarios are deferred.

- **Mix.** Per-schema weighting plus a global `read_ratio` (0.0–1.0). Within
  writes, the per-schema `update_ratio` from §1 governs insert-vs-update.
- **Read key sampling.** Reads draw keys from the same cohort distribution as
  writes. The sampling strategy is abstracted so a per-scenario override can be
  added later without major refactoring.
- **Rate.** One of `constant: <ops/s>`, `ramped: {from, to, over}`, or
  `stepped: [{rate, hold}, ...]`.
- **Duration.** One of `time: <Duration>`, `count: <ops>`, or
  `until_stop_condition`.
- **Stop conditions.** Optional list, ORed: `latency_p99_above`,
  `latency_p999_above`, `error_rate_above`, `external_signal`. A triggered stop
  is recorded as the scenario outcome, not a crash.
- **Transactional scope** (per scenario, KV mode). Optional field — omitted
  scenarios get `none`:
    - `none` *(default)* — no transaction wrapping; each `put()` standalone.
      Works against ATOMIC-mode caches (the GG8 default) without configuration.
    - `business_event` — one transaction wraps a single root-schema emission
      together with all transitive children produced by `parent-fk-ref` relations
      and cohort buckets. The whole subtree is the business event. Requires every
      target cache to be configured `CacheAtomicityMode.TRANSACTIONAL` (GG8 8.9+
      rejects atomic-cache operations inside transactions).
    - SQL-mode transaction semantics are designed but deferred; the
      insert/update/upsert distinction is preserved at the operation layer.
- **Colocation alignment.** When `transaction_scope: business_event` is paired
  with a relation chain that has no `affinity: true` column, validation emits a
  warning. With proper affinity, all puts in one event land on the same
  partition by construction.
- **Scenario root.** Each scenario declares one or more **root schemas**,
  weighted if multiple. Children are emitted transitively from their parent's
  business event, never enumerated directly.
- **Result.** Every scenario produces a structured outcome (achieved rate,
  latency percentiles, error counts, stop reason, wall time) written to
  `demoOutputDirectory/data-generator/runs/<run-id>/result.yaml`.
- **Note.** Transaction semantics may need refinement once exercised against
  the real GG8 and GG9 transaction APIs. The model leaves room for per-relation
  transaction-boundary tuning without restructuring core types.

The two named goals from the original CLAUDE.md map cleanly onto these
primitives:
- *Maximum Sustained Write* = `ramped` rate + `until_stop_condition` with a
  `latency_p99_above` threshold.
- *Demonstrate load over time* = `constant` rate + `time` duration +
  `latency_p99_above` threshold.

---

## §3 Cluster Targets & Output Backends

**In scope:**
- **GG8 KV** target via the GG8 thin client.
- **GG9 KV** target via the GG9 client (single client, no thin/thick split).
  Both targets consume cluster endpoints from `gridgain-demo-client-utils`
  (`ClientEndpoints`, `ClientEndpointsLoader`, `RuntimeContext`,
  `gg8-client-finder`, `gg9-client-finder`).

**Designed, deferred:**
- **SQL backends** (GG8 and GG9). The schema/column abstraction must already
  accommodate SQL; column types map cleanly to SQL column types; the
  insert/update/upsert distinction is preserved at the operation layer.
- **File backends** for the standalone deployment model: CSV first, Parquet
  later.
- **Queue backends** for the remote deployment model: GridGain Data Streamer
  first, Kafka later.

**Capabilities all targets must declare:**
- `supports_reads: bool`. KV targets do; file targets don't. A scenario with
  `read_ratio > 0` against a non-reading target is a validation error.
- `supports_transactions: bool`. Drives whether `transaction_scope:
  business_event` is honored or rejected.

**Connection config** lives under `ops.yaml` `targets:`. Endpoint addresses
reuse the yaml shape already supported by `ClientEndpointsLoader`. Auth and SSL
are declared per target. Secrets follow the project rule that config files may
contain secrets and must be gitignored.

---

## §4 Provisioning *(in scope: switchable per scenario)*

Per-scenario `provisioning:` field, one of `apply`, `emit`, or `skip`
*(default `skip`)*.

- **`emit`** writes artifacts to
  `demoOutputDirectory/data-generator/provisioning/`:
    - GG8 cache config XML.
    - GG9 SQL DDL (`CREATE ZONE`, `CREATE TABLE`).
    - Kubernetes YAML where applicable.
      Format set is target-specific.
- **`apply`** uses the cluster connection to create absent caches/tables (and
  zones for GG9). Idempotent: matching definitions are left untouched;
  mismatched definitions fail with a remediation message.
- **`skip`** assumes caches/tables already exist; mismatches surface as runtime
  errors with remediation.
- **Affinity translation.** `affinity: true` on a column becomes GG8
  `affinityKey` config and GG9 `COLOCATE BY`.
- **Out of scope:** dropping or recreating existing caches/tables. The user
  tears down explicitly.

---

## §5 Coordination Model

**Phase 1 *(in scope)*:** single-process generator. All scenario state, cohort
assignments, key-emission tracking, and metrics aggregation are in-memory.

**Phase 2 *(designed, deferred)*:** coordinator + workers. The contract below
must hold so phase 2 can be added without refactoring core types.

- **Subtask** = unit of work dispatched to one worker. Carries: target binding,
  schema slice, cohort assignment, key range, op count or duration, transaction
  scope.
- **Coordinator responsibilities:** parse config, plan the run (partition
  keyspace and cohort shares across workers), dispatch subtasks, collect
  per-subtask metrics and outcomes, aggregate results, persist final state.
- **Worker responsibilities:** execute subtasks, apply rate/ramp locally,
  report metrics, surface stop-condition triggers.
- **Communication channel:** intentionally not selected here. The contract is
  shaped so an HTTP, gRPC, or queue choice does not bleed into core types.

**Hard rule:** generator nodes never share compute or memory with the target
cluster. Honored at the deployment-manifest layer (separate node pools,
anti-affinity).

---

## §6 Configuration & State

**Two yaml files**, both with user-configurable paths (mirroring
`demoConfigFile`):

- `data.yaml` — schemas, columns, providers, relations, custom yaml-provider
  data, cohort buckets, per-schema `update_ratio`.
- `ops.yaml` — scenarios, targets, provisioning toggles, runtime config,
  observability.

**Schema versioning.** Each file carries a top-level `schema_version: <int>`.
Two independent monotonic versions: `CURRENT_DATA_SCHEMA_VERSION` and
`CURRENT_OPS_SCHEMA_VERSION`. The same migration-runner pattern used in the
plugin (`ConfigMigration` interface, ordered `MigrateVNtoVN+1` classes, runner
invoked before JSONSchema validation) applies.

**JSONSchema** files live in `src/main/resources/schema/data/` and
`src/main/resources/schema/ops/`. JSONSchemas are the primary structural
source of truth and record default values for documentation purposes even
though defaults are not used to populate runtime objects in the current phase.

**Validation pipeline:**
`migrate → JSONSchema validate → cross-element validate → deserialize →
assemble`.

Cross-element validation enforces:
- Relation referential integrity.
- Scenario-to-target capability compatibility:
    - `read_ratio > 0` requires `target.supports_reads = true`.
    - `transaction_scope: business_event` requires
      `target.supports_transactions = true`.
- `null_rate` not on relation columns.
- Affinity-vs-transaction warning from §2.

**Generated-output root.** All file output lives under
`demoOutputDirectory/data-generator/`:
- `provisioning/` — emitted cache/table/k8s artifacts.
- `runs/<run-id>/` — per-run `result.yaml` and `run.log.yaml`.
- `state/state.yaml` — generator state across runs.

**State file.** `state.yaml` carries per-schema sequence positions,
key-emission counts, cohort assignments resolved during the run, and the run
history index. It carries its own `schemaVersion`. **No migration support** —
mismatch is a hard error with remediation guidance ("tear down the generated
state directory and re-run from clean state"), matching the plugin's
`deployment.yaml` rule.

**Custom yaml-provider data.** End users reference yaml files from `data.yaml`
columns. The generator loads them through its own `YamlBackedProvider` wrapper,
so end users write only yaml.

---

## §7 Operability & Observability *(in scope: minimum viable; designed for
extension)*

OpenTelemetry is the single metrics pipe. SDK setup is a small block in
`ops.yaml`:
`otel: { exporter: otlp|prometheus|none, endpoint, ... }`.

**Minimum viable instrument set:**
- **Histograms** (per scenario, target, schema, op type — `op =
  put | get | tx_commit | tx_rollback`):
    - `data_generator.op.latency`.
- **Counters:**
    - `data_generator.op.count`.
    - `data_generator.op.errors`, tagged by exception class.
- **Gauges:**
    - `data_generator.in_flight`.
    - `data_generator.target_rate`.
    - `data_generator.observed_rate`.
- **Lifecycle events as OTel logs:** `scenario.started`, `scenario.stopped`
  (with reason), `provisioning.applied`, `state.persisted`.

**Extensibility.** The instrument list lives at one registration point so
adding instruments is a one-file change. Instrument names are not chosen at
call sites.

**Run log.** Each run also writes a multi-doc yaml log to
`runs/<run-id>/run.log.yaml`, matching the plugin's "Recording Command Runner
logs" pattern.

**Dashboard reuse.** When the run is executed via the plugin and the plugin's
configuration declares a Prometheus/Grafana monitor, the generator inherits the
OTel endpoint from that monitor — no parallel config. When run standalone,
`ops.yaml` is the source of truth.

---

## §8 Deployment

**Three deployment models, all designed; only Standalone implemented in the
first cut.**

- **Standalone *(in scope)*:** generator runs as a JVM process on a developer
  laptop or CI runner. Output backends in this mode are typically files
  *(deferred)* or a remote KV target *(in scope)*.
- **In-Cluster *(designed, deferred)*:** generator deployed into the same
  kubernetes environment as the target cluster, in a separate node pool.
  Reuses the plugin's existing `NodePoolTemplate` configuration; the generator
  does not re-implement node-pool provisioning.
- **Remote *(designed, deferred)*:** generator nodes deployed independently,
  streaming data into a queue (GG Data Streamer or Kafka).

**Hard rule:** generator pods never share a node pool with target-cluster
pods. Anti-affinity rules are emitted by `provisioning: emit` in deployment
manifests.

**k8s artifact reuse.** Where the plugin already produces `NodePool`,
`Deployment`, and `Service` templates, the generator's `provisioning: emit`
reuses them rather than parallel-implementing.

---

## §9 Plugin & UI Integration

- **Plugin → generator dependency only.** `gridgain-demo-gradle-plugin` may
  pull in the generator artifact and invoke it. The reverse is forbidden. The
  generator must build and run with no plugin dependency.
- **Plugin invocation.** A plugin task constructs `data.yaml`/`ops.yaml` paths
  from plugin context (already-resolved `demoConfigFile` and
  `demoOutputDirectory`), invokes the generator's CLI or programmatic entry,
  and surfaces results back into the plugin's run log.
- **UI integration is loose.** `gridgain-demo-ui` references the generator by
  URL or artifact; this spec does not commit to a tighter coupling.
  Look-and-feel consistency comes from sharing yaml shapes, not shared code.
- **Versioning lock-step.** The generator publishes from the same release
  pipeline as the plugin and the UI. Consumers must pin all three together.

---

## §10 Dependencies

- `net.datafaker:datafaker` — already wired in `build.gradle.kts` at version
  `2.5.4`.
- `gridgain-demo-client-utils` (`client-finder-common`, `gg8-client-finder`,
  `gg9-client-finder`) — connection substrate, no parallel implementation.
- OpenTelemetry SDK and exporters (OTLP, Prometheus).
- GridGain v8 thin client; GridGain v9 client.
- JSONSchema validation library (same one used by the plugin).
- SnakeYAML pinned at `1.33` (project-wide rule).

No additional dependencies beyond those above without explicit approval. The
generator must remain broadly applicable; minimizing dependencies is a stated
goal.

---

## §11 Out of Scope

For this specification:
- SQL backend implementation (designed; not built).
- Multi-node coordination implementation (designed; not built).
- File output backends (CSV, Parquet) implementation (designed; not built).
- Queue output backends (GG Data Streamer, Kafka) implementation (designed;
  not built).
- In-Cluster and Remote deployment manifests (designed; not built).

---

## §12 Future Work

Acknowledged direction; not designed in this document:
- Per-scenario read-skew override.
- Compute-task workloads against the cluster (beyond KV/SQL).
- Pluggable transaction-scope policies (e.g., per-relation transaction
  boundaries).
- End-user JVM custom providers via classpath plug-in.
- Schema-version migration for `state.yaml` (today: hard-fail on mismatch).
- Multi-cluster targeting within a single scenario (today: one target per
  scenario).
- Selectable runs over a list of scenarios in one invocation.

---

## §13 Verification (for any future implementation work)

End-to-end smoke validation that the implementation honors this spec:

1. **Config validation** — supply a malformed `data.yaml` (e.g., `null_rate`
   on a relation column, dangling `parent-fk-ref`, affinity warning trigger).
   Generator must reject with rich remediation messages and exit non-zero.
2. **Schema migration** — author a `data.yaml` at version N-1; the generator
   must auto-migrate to N before validation. Add a unit test for each
   migration step (matching the plugin's `ConfigMigrationTest.kt`).
3. **Provisioning emit** — run a scenario with `provisioning: emit` against
   GG8 and GG9 schemas, including an `affinity` column. Inspect emitted GG8
   XML for `affinityKey` and GG9 SQL for `COLOCATE BY`.
4. **Provisioning apply** — against a live single-node cluster of each
   version, run `apply` twice; second run is a no-op.
5. **KV write scenario** — run a `constant`-rate `business_event` scenario
   against a Customer→Order schema with cohort buckets. Confirm:
    - row counts per cohort match the declared shares within statistical
      tolerance,
    - all child rows of one parent land on the same partition (use cluster
      introspection),
    - `tx_commit` count equals the number of business events,
    - latency histograms and counters appear in the configured OTel exporter.
6. **Stop condition** — run a `ramped` scenario with a `latency_p99_above`
   threshold low enough to trigger; confirm clean stop and a `result.yaml`
   recording the stop reason.
7. **State persistence** — run a write-only scenario, then a follow-up
   read-heavy scenario in a fresh process pointed at the same
   `demoOutputDirectory`. Reads must hit the previously emitted keys; the
   `state.yaml` schemaVersion must match.
8. **Plugin invocation** — invoke the generator from a plugin task; confirm
   `demoConfigFile` and `demoOutputDirectory` flow through and the run log
   merges into the plugin's existing run log.

---

## Critical files (forward references for implementation work)

These do not exist yet; they are the anticipated landing spots based on
project conventions:

- `gridgain-demo-data-generator/src/main/kotlin/com/gridgain/demo/datagen/`
    - `config/ConfiguredState.kt` — `CURRENT_DATA_SCHEMA_VERSION`,
      `CURRENT_OPS_SCHEMA_VERSION` constants.
    - `config/ConfigMigration.kt` — interface and runner.
    - `config/ConfigurationParser.kt` — pipeline entry point.
    - `schema/`, `scenario/`, `target/`, `provisioning/`, `coordination/`,
      `state/`, `observability/`, `cli/` — capability-aligned packages.
- `gridgain-demo-data-generator/src/main/resources/schema/data/` and
  `.../schema/ops/` — JSONSchema files per version.

Build-tool-agnostic core; no `org.gradle.*` imports in core packages
(project-wide rule).

## Usage skill — read it, and keep it current

This repo ships a standalone usage skill at
`.claude/skills/gridgain-demo-data-generator/SKILL.md` — the config-surface + semantics contract
(ops.yaml/data.yaml, rate kinds, `transaction_scope`, distribution, provisioning, metrics). It
auto-loads when working in this repo and is referenced by consumers (e.g. the toolkit plugin's
`gridgain-demo-toolkit` skill).

**Maintenance rule (binding).** When you change the generator's config surface — an ops/data
schema field, a rate or value-source kind, `transaction_scope`/distribution semantics, the metrics
block, or the CLI — **update that SKILL.md in the same change and bump its *Last updated* date.**
The skill is standalone: it must never reference the gradle plugin or any demo. Prefer citing a
source file over duplicating volatile detail.
