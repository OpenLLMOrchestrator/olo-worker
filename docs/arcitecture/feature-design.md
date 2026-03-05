# Olo Feature Design — Specification

This document defines the **feature architecture** for Olo: cross-cutting behavior that runs before and/or after execution tree nodes (e.g. logging, quota, metrics, ledger, execution events). It aligns with [architecture-and-features](architecture-and-features.md), [creating-plugins-and-features](creating-plugins-and-features.md), and [feature-ordering-diagram](feature-ordering-diagram.md).

---

## Why Features Exist

Execution systems inevitably require behavior that applies **across many nodes**:

- **Logging** — Per-node or per-run logs.
- **Quota enforcement** — Limit concurrent runs or usage before execution.
- **Audit** — Record who did what and when.
- **Metrics** — Counts, latencies, tags per node or run.
- **Debugging** — Trace and inspect execution.
- **Event emission** — Stream execution events to UIs or downstream systems.
- **Ledger persistence** — Durable record of runs and node outcomes.

Embedding these concerns **inside plugins** leads to:

- **Duplicated logic** — Every plugin reimplements logging or metrics.
- **Inconsistent behavior** — Different plugins log or audit in different ways.
- **Difficult debugging** — Cross-cutting behavior is scattered and hard to toggle.
- **Fragile plugin implementations** — Plugins become heavy and coupled to infra.

Olo solves this using **Features**, which allow the execution engine to run **cross-cutting behavior around node execution** without modifying plugin implementations. Features are attached per pipeline (scope) and/or per node and run in defined **phases** (PRE, POST_SUCCESS, POST_ERROR, FINALLY). This gives new contributors a clear mental model: plugins do the work; features observe or guard around it.

---

## Goals

- **Cross-cutting behavior** — Features run around node execution without changing the execution tree or plugin code. Use for observability, policy, quota, ledger, and audit.
- **Phase-based hooks** — Clear phases (PRE, POST_SUCCESS, POST_ERROR, FINALLY) so feature authors choose when to run and what to do on success vs error.
- **Deterministic order** — Resolved feature lists have a defined merge order and execution order within each phase so behavior is predictable and testable.
- **Privilege separation** — **Internal** features can block execution and affect failure semantics; **community** features are observer-only (read, log, metrics) and must not fail the run.
- **Config-driven** — Features are enabled per pipeline via scope and/or per node; optional **contractVersion** for config compatibility.

---

## What Is a Feature?

A **feature** is a cross-cutting component that is **invoked by the execution engine** at defined moments around each node:

- **Before** the node runs (**PRE**).
- **After** the node runs — on success (**POST_SUCCESS**), on error (**POST_ERROR**), or **always** (**FINALLY**).

Features are **not** plugins: they do not execute business logic (no `execute()` or `createRuntime()`). They observe or influence the run (e.g. log, check quota, write to ledger, emit events). Plugins are invoked *inside* the node step; features wrap that step.

| Aspect | Plugin | Feature |
|--------|--------|---------|
| **Invoked when** | When a node of type PLUGIN runs and references the plugin | Before/after every node (when the feature is in the resolved list for that node) |
| **Contract** | execute(inputs) or createRuntime(config) | before(context), afterSuccess/afterError/afterFinally(context, result) |
| **Purpose** | Run model, vector store, tool, etc. | Log, quota, metrics, ledger, audit, policy |

Features are **registered** in a **FeatureRegistry** at worker startup and **resolved** per node by **FeatureAttachmentResolver** (node + scope + queue + required/excluded).

---

## Glossary

| Term | Meaning |
|------|---------|
| **Feature** | A registered component that implements one or more phase contracts (PreNodeCall, PostSuccessCall, PostErrorCall, FinallyCall, PreFinallyCall). Invoked by the executor around each node. |
| **FeatureRegistry** | Registry of features by name. Stores phase routing (isPre, isPostSuccess, isPostError, isFinally), applicableNodeTypes, contractVersion, and **privilege** (internal vs community). |
| **FeatureAttachmentResolver** | Resolves the effective **pre**, **postSuccess**, **postError**, and **finally** feature name lists for a node by merging node lists, scope features, queue-based rules (e.g. `-debug` → debug), and featureRequired / featureNotRequired. |
| **Phase** | When a feature runs: PRE (before node), POST_SUCCESS (after success), POST_ERROR (after throw), FINALLY (always after), or PRE_FINALLY (before + all three post moments). |
| **NodeExecutionContext** | **Immutable** context passed to every feature hook: nodeId, type, nodeType, tenantId, tenantConfigMap, queueName, pluginId, executionSucceeded, attributes. Read-only so features cannot alter execution semantics (prevents accidental or malicious mutation by community features). |
| **Privilege** | **Internal** = kernel-privileged (can block, throw, persist, enforce policy). **Community** = observer-only (read, log, metrics; must not block or mutate execution state). |

---

## Phase Model

### Phases

| Phase | When it runs | Typical use |
|-------|----------------|-------------|
| **PRE** | Once, before the node executes | Quota check, guard, policy (fail fast). |
| **POST_SUCCESS** | After the node completes without throwing | Persist result, success metrics, conditional logic. |
| **POST_ERROR** | After the node throws | Error reporting, retry bookkeeping, error metrics. |
| **FINALLY** | Always after the node (after POST_SUCCESS or POST_ERROR) | Logging, metrics, lightweight cleanup. |
| **PRE_FINALLY** | Before the node **and** after (all three post moments) | Feature that needs both pre and post (e.g. debug: log before and after). |

**When to use which:**

- **POST_SUCCESS / POST_ERROR** — Use for logic that may throw, has significant side effects, or must react specifically to success vs error (e.g. conditional persistence, error reporting). Implement **PostSuccessCall** and/or **PostErrorCall** (or **PreFinallyCall** with afterSuccess/afterError).
- **FINALLY / PRE_FINALLY** — Use for **non–exception-prone** code: logging, metrics, cleanup. Implement **FinallyCall** or **PreFinallyCall** (afterFinally). Keeps the finally phase predictable.

### Phase execution flow (per node)

For each execution tree node, the executor runs phases in this order:

```
    PRE  →  NODE EXECUTION  →  (POST_SUCCESS or POST_ERROR)  →  FINALLY
```

A single-page diagram: [feature-ordering-diagram.md](feature-ordering-diagram.md).

### Execution flow (diagram)

```
         +----------------------+
         |   Execution Engine   |
         +----------+-----------+
                    |
              resolve features
                    |
         +----------v-----------+
         | FeatureAttachment    |
         |     Resolver         |
         +----------+-----------+
                    |
           PRE feature list
                    |
              run PRE hooks
                    |
               Node Execute
                    |
        POST_SUCCESS / POST_ERROR
                    |
               FINALLY hooks
```

### Simple execution example

Concrete config and resolved execution:

**Pipeline:**

- `scope.features = ["metrics"]`

**Node A:**

- `type: plugin`
- `plugin: openai`
- `features: ["debug"]`

**Resolved execution (per node):**

| Phase | Features run |
|-------|----------------|
| **PRE** | debug, metrics |
| **NODE EXECUTION** | openai plugin |
| **POST_SUCCESS** | debug, metrics |
| **POST_ERROR** | (if node throws) debug, metrics |
| **FINALLY** | debug, metrics |

This single example clarifies how scope features and node features merge, and in which order phases run.

---

## Phase Contracts

Features implement one or more interfaces (or a single **PreFinallyCall** that covers before + all post moments):

| Contract | Method(s) | Phase(s) |
|----------|-----------|----------|
| **PreNodeCall** | `void before(NodeExecutionContext context)` | PRE (and pre part of PRE_FINALLY) |
| **PostSuccessCall** | `void afterSuccess(NodeExecutionContext context, Object nodeResult)` | POST_SUCCESS |
| **PostErrorCall** | `void afterError(NodeExecutionContext context, Object nodeResult)` | POST_ERROR |
| **FinallyCall** | `void afterFinally(NodeExecutionContext context, Object nodeResult)` | FINALLY |
| **PreFinallyCall** | `before` + `afterSuccess` + `afterError` + `afterFinally` | PRE_FINALLY (all moments) |

**Observer contracts (community):** **ObserverPreNodeCall** and **ObserverPostNodeCall** have the same method signatures but document **observer-only** semantics: read context, log, emit metrics; do not throw to fail the run or mutate execution state.

**NodeExecutionContext** (read-only) provides: `nodeId`, `type`, `nodeType`, `tenantId`, `tenantConfigMap`, `queueName`, `pluginId`, `executionSucceeded`, `attributes`, and any other fields the engine exposes. **Features must not mutate it.** NodeExecutionContext is **immutable** to guarantee that features cannot alter execution semantics. This prevents accidental or malicious modification of runtime state by community features; the executor and plugins see a consistent view of the run.

---

## Attachment and Resolution

Which features run for a given node is determined by **FeatureAttachmentResolver**, which merges:

1. **Node explicit lists** — `preExecution`, `postSuccessExecution`, `postErrorExecution`, `finallyExecution` (and legacy `postExecution` → all three post lists).
2. **Node shorthand** — `features` (each feature is routed to its phases per registry).
3. **Scope + queue** — Pipeline `scope.features`; **queue-based feature rules** (e.g. queue name ends with `-debug` → add `"debug"`). Queue-based rules are **defined in the worker configuration** and **evaluated by FeatureAttachmentResolver** when resolving the effective feature lists for a node.
4. **Required** — `featureRequired` (always include these unless excluded).
5. **Excluded** — `featureNotRequired` (always exclude from resolved lists).

**Merge order** (first occurrence wins; no duplicates):  
Node explicit → legacy postExecution → node features → scope + queue → featureRequired.  
Excluded: featureNotRequired.

**Applicability:** A feature can declare **applicableNodeTypes** (e.g. `"*"`, `"SEQUENCE"`, `"PLUGIN"`). It is only considered for nodes whose type matches. Example: QuotaFeature runs only on SEQUENCE (root) and is attached via scope, not per node.

**Order determinism:** The order of feature names in each resolved list (pre, postSuccess, postError, finally) is stable: scope.features order is preserved; node.features order is preserved. If the same feature appears in both node and scope, **node.features wins** for position (node is processed before scope in merge order). See [feature-ordering-diagram](feature-ordering-diagram.md) and [architecture-and-features §3.2.1](architecture-and-features.md#321-feature-execution-order-defined-contract).

---

## Privilege: Internal vs Community

Features are split into two privilege levels (similar to internal vs community plugins).

| Privilege | Registration | Allowed | Not allowed |
|-----------|--------------|---------|-------------|
| **Internal** | `registerInternal(...)` or `register(...)` | Block execution, throw (fail the run), mutate context semantics, persist ledger, enforce quota, audit, run in any phase | — |
| **Community** | `registerCommunity(...)` | Read context, log, emit metrics, append attributes (if supported) | Block execution, change plan, throw to fail run, mutate execution state |

**Enforcement:**

- **Internal:** If an internal feature throws in a pre hook, the executor **propagates** the exception and execution fails (e.g. QuotaExceededException).
- **Community:** If a community feature throws, the executor **catches**, **logs**, and **continues**. Community features must be observer-only; **NodeExecutionContext** is immutable so they cannot mutate it. Use **ObserverPreNodeCall** / **ObserverPostNodeCall** in contracts to document observer-only semantics.

Internal features are aggregated in **olo-internal-features** and registered in **InternalFeatures.registerInternalFeatures(...)** at worker startup. Community features are registered by the application (e.g. `FeatureRegistry.getInstance().registerCommunity(new MyFeature())`).

### Error propagation

| Feature type | Phase | If feature throws |
|--------------|-------|--------------------|
| **Internal** | PRE | **Execution stops**; exception propagates (e.g. QuotaExceededException). |
| **Internal** | POST_SUCCESS | **Execution fails**; exception propagates. |
| **Internal** | POST_ERROR | Execution continues unless the feature rethrows. |
| **Internal** | FINALLY | Same as POST_*; propagation depends on implementation. |
| **Community** | any | **Logged and ignored**; execution continues. Community features must not fail the run. |

This prevents confusion for feature authors: internal features can fail the run in PRE or POST_SUCCESS; community features are always catch-and-log.

---

## Performance expectations

Features run **around every node execution**. Therefore:

- **Feature hooks must be lightweight** — Avoid heavy computation, large allocations, or blocking I/O inside hooks.
- **Blocking operations should be avoided** — If a feature must do I/O (e.g. ledger write), prefer non-blocking or fire-and-forget where safe.
- **Heavy work should be asynchronous when possible** — Offload to a queue or background task rather than doing it synchronously in the hook.

The executor **assumes feature hooks are fast and deterministic**. Without this, a feature that does a **network call per node** would slow every run and make behavior non-deterministic. Design features for low latency and minimal side effects in the hot path.

---

## Feature idempotency

Features should be **idempotent where possible**. Because workflows may **retry nodes** or **re-execute steps**, feature logic such as ledger writes or event emission should **tolerate duplicate invocation** (e.g. deduplicate by runId+nodeId, or use upsert semantics). This will save future production incidents when retries or replays cause the same feature hook to run more than once for the same logical operation.

---

## Lifecycle

### Registration

- **At worker startup** — All features (internal and community) are registered with **FeatureRegistry**. No SPI; registration is explicit so the worker controls which features are loaded.
- **Internal** — Registered via **InternalFeatures.registerInternalFeatures(registry, sessionCache, runLedgerOrNull, executionEventSinkOrNull)** (e.g. DebuggerFeature, QuotaFeature, MetricsFeature, RunLevelLedgerFeature, NodeLedgerFeature, ExecutionEventsFeature).
- **Community** — Registered by the application with **FeatureRegistry.registerCommunity(instance)**.

### Invocation

- **Per node** — For each execution tree node, the engine resolves the four lists (pre, postSuccess, postError, finally), then runs PRE → node → POST_SUCCESS or POST_ERROR → FINALLY. Each list is executed in order; each feature is invoked once per phase it participates in.
- **No per-request registration** — The set of features is fixed after startup.

### Shutdown

- **ResourceCleanup** — Features that hold resources (e.g. thread locals, meters) should implement **ResourceCleanup** and override **onExit()**. The worker calls **runResourceCleanup()** on shutdown (from WorkerBootstrapContext), which invokes **onExit()** on all registered plugins and features.

---

## Versioning and Compatibility

- **contractVersion** — Features declare a **contractVersion** (e.g. `@OloFeature(contractVersion = "1.0")`). Pipeline scope can reference features with a version for compatibility checks. See [versioned-config-strategy](versioned-config-strategy.md).
- **Config validation** — Before a run, the config validator can ensure that the pipeline’s scope.features expect a compatible feature version (e.g. runtime ≥ expected).

---

## Integration Points

| System | How features are used |
|--------|------------------------|
| **Execution engine** | For each node: resolve pre/post lists → run pre → execute node → run postSuccess or postError → run finally. NodeExecutor and execution tree drive this. |
| **Run ledger** | **RunLevelLedgerFeature** (root) and **NodeLedgerFeature** (per node) write to the run ledger when OLO_RUN_LEDGER is enabled. See [run-ledger-schema](run-ledger-schema.md). |
| **Execution events** | **ExecutionEventsFeature** emits semantic events (planner, tool, model, workflow) for chat UI; runId from workflow input. See [execution-events](execution-events.md). |
| **Quota** | **QuotaFeature** (PRE, SEQUENCE only) runs once at root; checks tenantConfig.quota.softLimit/hardLimit and OloSessionCache.getActiveWorkflowsCount; throws QuotaExceededException if exceeded. |
| **Metrics** | **MetricsFeature** (PRE_FINALLY) increments counters (e.g. olo.node.executions) with tags tenant, nodeType in afterFinally. |
| **Debug** | **DebuggerFeature** (PRE_FINALLY) logs before/after at INFO; auto-attached when queue name ends with `-debug`. |

---

## Module Layout

```
olo-worker-features     # Contracts: PreNodeCall, PostSuccessCall, PostErrorCall, FinallyCall, PreFinallyCall;
                        # FeatureRegistry, FeatureAttachmentResolver, NodeExecutionContext, ResolvedPrePost
olo-annotations         # @OloFeature(name, phase, applicableNodeTypes, contractVersion); FeatureInfo
olo-internal-features   # Aggregates internal features; InternalFeatures.registerInternalFeatures(...)
olo-feature-debug       # DebuggerFeature (PRE_FINALLY, *)
olo-feature-quota       # QuotaFeature (PRE, SEQUENCE; scope only)
olo-feature-metrics     # MetricsFeature (PRE_FINALLY, *); ResourceCleanup
olo-run-ledger          # RunLevelLedgerFeature, NodeLedgerFeature, ExecutionEventsFeature (when ledger/events enabled)
```

---

## Built-in Features (Summary)

| Feature | Phase | Applicable | Purpose |
|---------|-------|------------|---------|
| **debug** | PRE_FINALLY | * | Log before/after each node. Auto-attached when queue ends with `-debug`. |
| **quota** | PRE | SEQUENCE | Check tenant active workflow count vs soft/hard limit; throw if exceeded. **Must run only on root, once per run** — enable via scope only. |
| **metrics** | PRE_FINALLY | * | Increment olo.node.executions (tenant, nodeType) in afterFinally. |
| **RunLevelLedgerFeature** | (root) | Root | Run-level ledger entries when OLO_RUN_LEDGER=true. |
| **NodeLedgerFeature** | PRE_FINALLY | * | Per-node ledger entries. |
| **ExecutionEventsFeature** | PRE_FINALLY | * | Emit execution events for chat UI (planner, tool, model, workflow). |

---

## Summary

| Aspect | Design |
|--------|--------|
| **Identity** | Feature name (e.g. `debug`, `quota`); registered in FeatureRegistry; enabled via scope and/or node. |
| **Contract** | One or more of PreNodeCall, PostSuccessCall, PostErrorCall, FinallyCall, PreFinallyCall. |
| **Phase flow** | PRE → NODE → (POST_SUCCESS or POST_ERROR) → FINALLY. |
| **Resolution** | FeatureAttachmentResolver merges node explicit, node features, scope, queue, required; excludes featureNotRequired. |
| **Privilege** | Internal (can block, throw) vs Community (observer-only; catch-and-log on throw). |
| **Context** | NodeExecutionContext (immutable); tenantId, nodeId, type, nodeType, tenantConfigMap, etc. |
| **Lifecycle** | Register at startup; invoke per node per phase; ResourceCleanup.onExit() at shutdown. |
| **Performance** | Hooks must be lightweight; avoid blocking/heavy work per node; executor assumes fast, deterministic hooks. |
| **Idempotency** | Features should tolerate duplicate invocation (retries, replays); ledger/event logic should be idempotent where possible. |
| **Error propagation** | Internal PRE/POST_SUCCESS throw → execution fails; Internal POST_ERROR → continues unless rethrow; Community any → logged and ignored. |
| **Queue rules** | Queue-based feature rules (e.g. `-debug` → debug) are in worker config; evaluated by FeatureAttachmentResolver. |

This design keeps features **cross-cutting**, **phase-based**, and **privilege-aware**. For step-by-step creation and pipeline config, see [creating-plugins-and-features](creating-plugins-and-features.md) Part 2. For full execution order and built-in feature behavior, see [architecture-and-features](architecture-and-features.md) and [feature-ordering-diagram](feature-ordering-diagram.md).

---

## Potential future risks (minor)

Things to keep in mind for v2; not required now.

**Feature explosion** — Over time you may have many features: metrics, quota, debug, ledger, audit, security, policy, cost, trace, cache, retry, … So eventually you may want **feature categories** (e.g. observability, policy, audit) or a way to group and configure them. Not needed for the current set.

**Async features** — Some features may eventually want an **afterFinallyAsync()** (or similar) that runs after the synchronous FINALLY phase, so that heavy or non-critical work (event streaming, analytics, audit pipelines) does not block the executor. This would require a defined contract and execution model (e.g. fire-and-forget vs bounded queue) so that shutdown and backpressure are handled. Optional future direction.
