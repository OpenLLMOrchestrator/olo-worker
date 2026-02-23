# Plan: Immutable config snapshots, execution version pinning, tenant execution limiter

This document outlines the design and implementation plan for three related features that improve safety and isolation of pipeline execution.

## Implementation status (done)

| Feature | Status | Notes |
|---------|--------|--------|
| **Immutable config snapshots** | Implemented | `ExecutionConfigSnapshot` (olo-worker-execution-context) holds tenantId, queueName, pipelineConfiguration, snapshotVersionId. Activity creates one at run start; `ExecutionEngine.run(ExecutionConfigSnapshot, ...)` uses it (no global config reads during run). |
| **Execution version pinning** | Implemented | `Routing.configVersion` (optional); `LocalContext.forQueue(tenant, queue, configVersion)` validates loaded config version; snapshot carries `snapshotVersionId`. |
| **Tenant execution limiter** | Implemented | `TenantExecutionLimiter` (per-tenant semaphore), `OLO_TENANT_MAX_CONCURRENT_RUNS` (default 10, 0 = no limit). Activity acquires before run, releases in `finally`. |

---

## 1. Immutable config snapshots

### Goal

Each execution run must use a **single, immutable** snapshot of the pipeline configuration. No mid-run reads from the global config store, and no mutability of the config object during the run.

### Current state

- **LocalContext** already creates a **deep copy** of the pipeline config from `GlobalConfigurationContext` when the activity starts (`LocalContext.forQueue(tenantId, queueName)`).
- The copy is passed to `ExecutionEngine.run(config, ...)` and the engine never touches `GlobalConfigurationContext` again for that run.
- So we already have “one snapshot per run,” but it is not explicitly named or versioned, and the in-memory `PipelineConfiguration` object may still be structurally mutable (e.g. mutable maps/lists inside).

### Planned changes

| Item | Description |
|------|-------------|
| **Snapshot type** | Introduce an **ExecutionConfigSnapshot** (or extend LocalContext) that holds: (1) the deep-copied `PipelineConfiguration`, (2) an optional **snapshot version id** (e.g. config version + timestamp or revision), (3) tenant id and queue name. The activity creates one snapshot at the start of `runExecutionTree` and passes only this snapshot to the execution engine. |
| **Immutability** | Where possible, make the snapshot’s config **structurally immutable**: use unmodifiable collections for `pipelines`, `variableRegistry`, node children, etc., so that code cannot accidentally mutate the snapshot. This may require a “freeze” step after deep copy or a dedicated read-only view type. |
| **No global reads during run** | Document and enforce: after the snapshot is taken, `ExecutionEngine` and `NodeExecutor` must not call `GlobalConfigurationContext.get()` or any config loader. All config access goes through the snapshot. Add a code-review / test invariant if useful. |
| **Placement** | Snapshot creation stays in the **activity** (e.g. in `OloKernelActivitiesImpl.runExecutionTree`): resolve tenant/queue (and optionally version, see §2), get config from global context, deep-copy, wrap in `ExecutionConfigSnapshot`, pass to `ExecutionEngine.run(snapshot, ...)`. |

### Acceptance

- Every run uses exactly one snapshot created at activity start.
- Snapshot is not mutated during the run; engine and features read only from it.
- Optional: snapshot carries a version id for auditing and version pinning (§2).

---

## 2. Execution version pinning

### Goal

An execution run is tied to a **specific pipeline config version**. Replays and retries use the same version; new deployments or config changes do not affect in-flight or replayed runs.

### Current state

- **OLO_CONFIG_VERSION** (e.g. `1.0`) is used at **bootstrap** to load config from Redis/DB/file. One version per (tenant, queue) is loaded into `GlobalConfigurationContext`.
- There is no per-**execution** or per-**workflow** config version; all runs for a tenant+queue use whatever is currently in the global context (which was loaded at worker startup).

### Planned changes

| Item | Description |
|------|-------------|
| **Version in workflow input** | Add an optional **config version** to the workflow input so the client can pin the run to a version. Options: (a) **Routing**: e.g. `configVersion` or `pipelineVersion` on `Routing`; (b) **Metadata**: e.g. `metadata.configVersion`; (c) **Context**: e.g. `context.configVersion`. Recommendation: **Routing** (with optional fallback to env default) so version is visible and easy to validate. |
| **Versioned config store** | Extend **GlobalConfigurationContext** (or add a parallel store) to hold config per **(tenant, queue, version)**. At bootstrap, load one or more versions per tenant+queue (e.g. current “default” version + optional pinned versions). Alternatively: keep single version in memory and **load on demand** by version when the activity runs (with cache). |
| **Resolution in activity** | In `runExecutionTree`, resolve config by **(tenantId, queueName, configVersion)**. If `configVersion` is missing from input, use **OLO_CONFIG_VERSION** (or a “default” version). Look up the snapshot for that triple and create the immutable snapshot (§1). If the requested version is not loaded, either fail fast or trigger on-demand load (and optionally cache). |
| **Replay safety** | Workflow code must not depend on “current” config; it only passes through the same `WorkflowInput` (including version) to the activity. So replay sees the same version and the activity loads the same snapshot again. |

### Design options

- **A. Multi-version in memory**: Bootstrap loads several versions per tenant+queue (e.g. “1.0”, “1.1”). Lookup by (tenant, queue, version). Bounded memory; versions must be known at bootstrap.
- **B. On-demand load by version**: When the activity runs with a version not in memory, load that version from Redis/DB/file once, cache in a versioned store, then create the snapshot. Allows arbitrary versions without loading all at bootstrap.
- **C. Single version + strict pinning**: Keep current single-version load; add `configVersion` to input only for validation (fail if it does not match the loaded version). No multi-version store yet.

Recommendation: start with **C** (validation-only) or **A** (small fixed set of versions), then add **B** if needed.

### Acceptance

- Workflow input can carry an optional config/pipeline version.
- Activity resolves config by (tenant, queue, version) and uses that for the run.
- Replays use the same version from the same input and get the same config snapshot.

---

## 3. Tenant execution limiter (thread isolation)

### Goal

Limit concurrency per tenant and optionally isolate tenant execution (e.g. so one tenant cannot starve others or monopolize worker threads).

### Current state

- **Redis**: We already **INCR**/ **DECR** `&lt;tenantId&gt;:olo:quota:activeWorkflows` at activity start/finish. This is observable for monitoring and can be used by an external limiter.
- There is no in-process **concurrency limit** per tenant (e.g. semaphore) and no per-tenant thread pool.

### Planned changes

| Item | Description |
|------|-------------|
| **Per-tenant concurrency cap** | Allow a maximum number of **concurrent** runs per tenant (e.g. 10). Before starting the tree in `runExecutionTree`, acquire a permit (e.g. from a **per-tenant semaphore**). Release in a `finally` block when the run ends. If the permit cannot be acquired, either **block** (with timeout) or **fail** the activity (e.g. “tenant concurrency limit exceeded”). |
| **Semaphore store** | In-memory: **Map&lt;tenantId, Semaphore&gt;** (e.g. max N permits per tenant). Created on first use; configurable N from env or tenant config. No cross-worker coordination; each worker enforces only its own concurrency. |
| **Optional: Redis-based limit** | Use the existing **activeWorkflows** counter: before running, if `GET &lt;tenantId&gt;:olo:quota:activeWorkflows` ≥ configured limit, reject or block. Requires a “max allowed” per tenant (from env or tenant config). |
| **Thread isolation (optional)** | Run activities for a given tenant on a **dedicated thread pool** (e.g. one `ExecutorService` per tenant, or a bounded pool per tenant). That way one tenant’s slow or blocking work does not block other tenants’ activities. Integration point: Temporal’s activity execution is already thread-pooled; per-tenant pools would require dispatching activity execution to tenant-specific executors (e.g. in a custom activity worker or wrapper). |

### Design options

- **A. In-memory semaphore per tenant** (single worker): Simple, fast. Limit is per-worker; total concurrency = sum over workers. No cross-process coordination.
- **B. Redis-based global limit**: Before INCR, check current value; if already ≥ max, do not INCR and fail (or wait/retry). Gives a global per-tenant limit across all workers. Requires “max active” config per tenant.
- **C. Per-tenant executor**: Route activity execution for tenant T to `executorForTenant.get(T).submit(...)`. Isolates threads by tenant; more complex and may require careful tuning (pool sizes, queue limits).

Recommendation: implement **A** first (per-tenant semaphore, configurable max concurrency from env or tenant config); add **B** if a global limit is required; consider **C** only if thread isolation per tenant is a hard requirement.

### Configuration

- **Env**: e.g. `OLO_TENANT_MAX_CONCURRENT_RUNS=10` (default per tenant). Or per-tenant in `olo:tenants` config: `maxConcurrentRuns`.
- **Semaphore lifecycle**: Lazy-init per tenant; optional cleanup for tenants that disappear (e.g. TTL or periodic prune).

### Acceptance

- Configurable max concurrent runs per tenant (in-memory semaphore or Redis).
- Activity acquires a permit before running the tree and releases in `finally`.
- Optional: tenant-specific thread pool for isolation.

---

## 4. Implementation order and dependencies

| Phase | Feature | Depends on | Notes |
|-------|---------|------------|--------|
| 1 | **Immutable config snapshots** | — | Formalize snapshot type, freeze config, ensure no global reads during run. |
| 2 | **Execution version pinning** | Snapshot (1) | Add version to input, versioned lookup, snapshot for (tenant, queue, version). |
| 3 | **Tenant execution limiter** | — | Can be done in parallel with 1–2. Semaphore + optional Redis check. |
| 4 | **Thread isolation (optional)** | Limiter (3) | Per-tenant executor if needed. |

---

## 5. Files and touch points (summary)

- **Immutable snapshots**: `LocalContext` (or new `ExecutionConfigSnapshot`), `OloKernelActivitiesImpl.runExecutionTree`, `ExecutionEngine.run` signature, `PipelineConfiguration` / deep-copy freeze.
- **Version pinning**: `Routing` or workflow input model (config version field), `GlobalConfigurationContext` or versioned config store, `OloKernelActivitiesImpl` (resolve by version), bootstrap (load one or more versions).
- **Tenant limiter**: `OloKernelActivitiesImpl.runExecutionTree` (acquire/release), new `TenantExecutionLimiter` or similar (semaphore map), config (env or tenant config for max concurrency).

---

## 6. Related docs

- [architecture-and-features.md](architecture-and-features.md) — execution flow, activity vs workflow.
- [multi-tenant.md](multi-tenant.md) — tenant ids, Redis keys, quota key.
- [versioned-config-strategy.md](versioned-config-strategy.md) — config and plugin/feature contract versions.
