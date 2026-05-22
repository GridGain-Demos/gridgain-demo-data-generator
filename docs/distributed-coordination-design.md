# Distributed Data-Generator — Coordination Design

**Status:** Draft. Frames C1 of the broader telemetry/distribution plan
([there-are-three-immediate-fluttering-sundae.md](../../../.claude/plans/there-are-three-immediate-fluttering-sundae.md)).
Phase-2 model in the data-generator's own `CLAUDE.md` §5 is the contract this fills in.

## Goal

Run a single scenario across N pods in the target cluster's k8s environment, with
horizontal scaling for throughput. Workers split the key space; one acts as
coordinator (leader-elected) and is responsible for assignment, state
persistence, and aggregate reporting.

## Hard constraints

- **No node-pool sharing with target cluster.** Honored at manifest layer via a
  dedicated `NodePoolTemplate` reference plus pod anti-affinity rules.
- **Single binary.** The leader is just a worker that holds the lease. No
  separate coordinator image — operationally simpler, and lets any pod take over
  on leader death.
- **Per-pod identity in metrics.** `service.instance.id` already set in A1 via
  the K8s downward API. Distributed runs add a `partition` attribute on
  per-tick instruments.

## Partitioning model

Each scenario gains an optional `distribution:` block:

```yaml
scenarios:
  - name: load-distributed
    target: gg9-trip
    root_schemas: [customer]
    rate: { kind: constant, ops_per_second: 200 }
    duration: { kind: count, value: 100000 }
    distribution:
      replicas: 4
      partition_count: 16
```

Rules:
- `distribution:` absent → single-pod (existing) behavior. No silent default.
- `distribution:` present → all sub-fields required (per CLAUDE.md "no defaults
  on template classes"). `partition_count >= replicas`.
- The scenario's `rate` and `duration` are **per-run totals**, not per-pod. The
  coordinator divides them across active workers.

**Assignment.** The coordinator owns a `partition_count`-sized array of
partition slots. On worker join/leave it computes a balanced assignment
(`ceil(partition_count / live_workers)` per worker, with stragglers picking up
the remainder) and writes it to a shared ConfigMap. Workers read the ConfigMap
and execute the partition slices they own.

**Key-space slicing.** A partition is an integer in `[0, partition_count)`. Each
worker filters its sequence/cohort emission by `hash(key) % partition_count ∈
assigned_set`. Sequence value sources start from `partition_id` and step by
`partition_count` so two workers never emit the same key.

## Leader election

`coordination.k8s.io/v1` `Lease` named `data-generator-<scenario>-leader` in the
data-gen Job's namespace. All pods race to acquire on startup; the holder is
the coordinator. Lease has a 15-second duration and is renewed every 5 seconds
(standard k8s controller convention).

**Client:** Fabric8 K8s client. Type-safe Lease API + native watch primitive
for follower-side leader-death detection; standard k8s controller pattern. Adds
~10 MB to the data-gen image. The kubectl-shell-out alternative was rejected
for the slow subprocess cadence and brittle exit-code parsing.

## State

Today: [`StatePersister`](../data-generator-core/src/main/kotlin/com/gridgain/demo/datagen/state/StatePersister.kt)
owns a single state file (sequence positions, key counts, cohort assignments).

Distributed: **leader-only writes**.
- Workers maintain their partition slice of state in memory.
- Workers publish progress to a per-pod status ConfigMap
  (`data-generator-worker-<pod-name>`) at a fixed cadence (5s).
- Leader reads worker ConfigMaps on its own clock, merges, and is the sole
  writer of the on-disk `state.yaml`.
- On graceful shutdown, the leader does a final merge + write before releasing
  the lease.

`StatePersister` gets a `writable: Boolean` constructor flag. Followers
instantiate it with `writable=false` and any write attempt throws.

## Failure semantics

- **Worker death.** Worker's lease in its status ConfigMap expires (or the
  ConfigMap's `resourceVersion` stops advancing). Leader detects via watch or
  periodic poll → reassigns its partitions to surviving workers → bumps
  `data_generator.coordinator.rebalances`.
- **Leader death.** Lease holder dies. Lease expires after 15s
  (`leaseDurationSeconds`). A follower acquires → reads the on-disk
  `state.yaml` (last leader's write) + the current worker ConfigMaps → resumes
  assignment from there. Up to one in-flight tick can double-emit during the
  switch; documented as acceptable for this generator's load-test mission.
- **Network partition.** One side acquires lease, the other can't renew. The
  side that can't reach the API server stops emitting (no lease → no
  assignment). When connectivity returns, that side rejoins as a worker.

## OTel additions

Coordinator-only instruments, registered when (and only when) this pod holds
the lease:
- `data_generator.coordinator.worker_count` (gauge, observable)
- `data_generator.coordinator.partition_assignments` (gauge, observable, per
  `service.instance.id` attribute)
- `data_generator.coordinator.rebalances` (counter)

Workers emit the existing instrument set plus an additional `partition`
attribute on every per-tick operation. `service.instance.id` already
distinguishes them (A1).

## Schema changes

- **`ops.yaml` `CURRENT_OPS_SCHEMA_VERSION`** bumps from 2 → 3.
- New `MigrateV2toV3` is a no-op: existing scenarios stay single-pod by virtue
  of `distribution:` being absent. The migration just bumps the version
  pointer; the JSONSchema for v3 adds the `distribution` block as optional.

## K8s manifest shape

New `DistributedDataGeneratorManifestWriter` in the plugin emits:
- **Deployment** with `replicas` = `distribution.replicas` (instead of a Job).
  Pod template same as today's Job container — image, env (including A1's
  pod-identity env vars), ConfigMap mounts.
- **ServiceAccount** for the pods.
- **Role** + **RoleBinding** scoped to the namespace, granting:
    - `coordination.k8s.io/leases` — `get,list,watch,create,update,patch`
    - `configmaps` — `get,list,watch,create,update,patch` (status ConfigMaps)
- **PodAntiAffinity** — `preferredDuringSchedulingIgnoredDuringExecution` on
  the `gridgain.com/cluster` label of GG cluster pods so data-gen pods
  schedule away from target nodes. Best-effort, not required — keeps the
  generator demo-friendly on small clusters. The CLAUDE.md hard rule about
  node-pool separation is still honored via the dedicated `NodePoolTemplate`;
  anti-affinity is belt-and-braces.

Templates land in `src/main/resources/templates/k8s/data-generator-distributed/`.

`InClusterJob` (single-pod) stays unchanged. `DataGeneratePlan` grows a sibling
`InClusterDistributed` variant; the action layer routes by presence of the
`distribution:` block.

## Resolved decisions

| Decision | Resolution |
|----------|-----------|
| K8s client | **Fabric8 K8s client** (see Leader election) |
| Anti-affinity strength | **`preferredDuringScheduling`** (see K8s manifest shape) |
| Worker reporting cadence | **5 s** (matches lease renew clock) |
| Lease duration / renew | **15 s / 5 s** (standard k8s controller defaults) |
| Schema-migration scope | **Bump now** alongside C2; `MigrateV2toV3` is a no-op |
