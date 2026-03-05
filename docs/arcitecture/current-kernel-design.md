# Olo Kernel — Current Design (Microkernel View)

This document explains how the **current** Olo worker codebase maps to a **microkernel-style kernel** and surrounding modules. It describes the structure as it exists today, not a large refactor plan, so contributors can see where kernel responsibilities live right now.

---

## 1. Core Principle: Microkernel

The **kernel** should contain only what must remain stable:

- **Execution contracts** – how nodes run.
- **Variable model** – how data flows through a run.
- **Feature contracts** – how cross-cutting behavior wraps nodes.
- **Plugin contracts** – how execution plugins expose capabilities.
- **Resource contracts** – how connections and secrets are referenced.
- **Event contracts** – how execution events are described.

Everything else (Temporal, HTTP, OpenAI, DBs, planners, UI) is outside the kernel and should integrate **via contracts**, not direct dependencies.

Today, kernel-like responsibilities live across a few modules:

- `olo-worker-execution-tree` – execution tree shape and config.
- `olo-worker-execution-context` – per-run context and snapshots.
- `olo-worker-features` – feature model and resolution.
- `olo-worker-plugin` – plugin contracts and registry.
- `olo-worker-protocol` – protocol interfaces for the worker.

These modules together form the **current kernel surface**.

---

## 2. Current Kernel Surface by Responsibility

### 2.1 Execution and Nodes

- **Execution tree shape** (`olo-worker-execution-tree`):
  - `ExecutionTreeNode` – node definition: id, `NodeType`, children, `pluginRef`, input/output mappings, `params`, timeouts, optional `executionMode` override.
  - `NodeType` – structural node kind (SEQUENCE, IF, PLUGIN, PLANNER, JOIN, etc.).
  - `ExecutionTreeConfig` / configuration loader – how trees are loaded from pipeline config.

- **Execution engine** (`olo-worker`):
  - `ExecutionEngine` – runs the tree: pre → execute → postSuccess/postError → finally.
  - `NodeExecutor` / handlers – dispatch per node type.
  - `VariableEngine` – variable map with IN/INTERNAL/OUT and mappings.

These classes effectively implement the **Node / NodeExecutor / ExecutionResult / VariableStore** concepts described in the kernel sketch.

### 2.2 Plugins (Execution Contracts)

- **Contracts and registry** (`olo-worker-plugin`):
  - `ExecutablePlugin` – base contract:
    - `Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig)`
    - `default ExecutionMode executionMode()` – declares default execution mode (WORKFLOW, LOCAL_ACTIVITY, ACTIVITY, CHILD_WORKFLOW).
  - `ExecutionMode` – enum for execution modes (workflow vs activities vs child workflow).
  - Specific contracts: `ModelExecutorPlugin`, `EmbeddingPlugin`, `VectorStorePlugin`, `ImageGenerationPlugin`.
  - `PluginRegistry` – tenant-scoped registry by `(tenantId, pluginId)`; returns `ExecutablePlugin` implementations.
  - `PluginProvider` – creates plugin instances per node.

- **Worker protocol** (`olo-worker-protocol`):
  - `PluginExecutor` – kernel-facing protocol:
    - `execute(String pluginId, String inputsJson, String nodeId)`
    - `toJson(Map<String,Object>)` / `fromJson(String)`.

- **Registry-backed executor** (`olo-worker-plugin`):
  - `RegistryPluginExecutor` – implements `PluginExecutor`:
    - Resolves plugins from `PluginRegistry`.
    - Invokes `ExecutablePlugin.execute(...)`.

Together these form the **current plugin kernel**: a stable contract for execution plugins and a registry + executor that hides plugin discovery from the worker.

### 2.3 Features (Cross-Cutting Runtime)

- **Feature runtime** (`olo-worker-features` and feature modules):
  - `FeatureAttachmentResolver` – resolves which features run for each node (pre, postSuccess, postError, finally).
  - `NodeExecutionContext` – immutable per-node context passed to features.
  - Feature contracts – pre/post hooks (logging, metrics, quota, ledger, events, etc.).

- **Integration with execution** (`olo-worker`):
  - Node handlers call:
    - `runPre(...)` → node execution → `runPostSuccess/postError` → `runFinally`.

This is the **current FeatureRuntime**: a stable pipeline for features that wrap node execution.

### 2.4 Variables and Context

- **Variable model** (`olo-worker-execution-tree` + `olo-worker`):
  - Variable registry – IN / INTERNAL / OUT contracts from config.
  - `VariableEngine` – runtime map, plus inputMappings/outputMappings/resultMapping for data flow.

- **Execution context** (`olo-worker-execution-context`):
  - `ExecutionConfigSnapshot` – immutable snapshot per run (tenantId, pipeline config, queue, etc.).
  - `LocalContext` – per-run context used by the worker.

These classes implement the **VariableStore** and **ExecutionContext** ideas from the kernel sketch.

### 2.5 Resources: Connections and Secrets (Design)

Runtime implementations are still in progress, but the **design** lives in:

- `docs/arcitecture/connection-manager-design.md` – ConnectionRuntimeManager, ConnectionResolver, ResourceRuntimeManager, ConnectionKey/ResourceKey.
- `docs/arcitecture/secret-architecture.md` – SecretResolver, SecretRegistry, SecretResolutionContext.

Today, connections and secrets are not yet first-class kernel modules, but the contracts are being defined so the kernel can expose:

- `ConnectionResolver` / `ResourceRuntimeManager` interfaces.
- `SecretResolver` and `SecretRef`-style references.

### 2.6 Events and Ledger

- **Event-like concerns** (today):
  - `run-ledger` module – `RunLevelLedgerFeature`, `NodeLedgerFeature`; record run/node data.
  - `docs/execution-events.md` – execution events design.
  - `ExecutionEventSink` – event sink abstraction used for observability.

These are the seeds of a future **Event Bus** inside the kernel (event contracts + `EventPublisher`), with transports (Kafka, WebSocket, etc.) as adapters.

---

## 3. Where Temporal Lives Today

Temporal is **outside** the kernel:

- `olo-worker`:
  - `OloKernelWorkflow` / `OloKernelWorkflowImpl` – Temporal workflow.
  - `OloKernelActivitiesImpl` / `ExecuteNodeDynamicActivity` – Temporal activities.
  - They call the execution engine and `PluginInvoker`.

The kernel contracts (`ExecutablePlugin`, `ExecutionTreeNode`, `VariableEngine`, features) do **not** depend on Temporal. Temporal runs the kernel, but the kernel is Temporal-agnostic.

This effectively corresponds to the future `olo-runtime-temporal` module in the proposed architecture.

---

## 4. Current Dependency Direction

At a high level, current dependencies follow this direction:

- **Top-down**
  - `olo-worker` (Temporal workflow, activities, engine)
    → `olo-worker-execution-tree` (ExecutionTreeNode, config)
    → `olo-worker-features` (features, feature runtime)
    → `olo-worker-plugin` (plugin contracts/registry)
    → `olo-worker-protocol` (PluginExecutor).

- **Plugins**
  - `olo-plugin-*` and `olo-tool-*` modules depend on:
    - `olo-worker-plugin` (ExecutablePlugin, ModelExecutorPlugin, PluginRegistry).
    - `olo-config` (TenantConfig).
  - They do **not** depend on `olo-worker` internals or Temporal.

This matches the **microkernel rule**: the kernel defines contracts; worker, Temporal, and plugins depend on those contracts, not vice versa.

---

## 5. Summary: Current Kernel in One Diagram

Conceptually, the current stack looks like:

```
               ┌────────────────────────┐
               │        OLO API         │   (HTTP/Web UI, SDK)
               └───────────┬────────────┘
                           │
               ┌───────────▼────────────┐
               │      OLO WORKER        │   (Temporal workflows + activities)
               │  ExecutionEngine       │
               │  NodeExecutor          │
               │  PluginInvoker         │
               └───────────┬────────────┘
                           │
               ┌───────────▼────────────┐
               │     KERNEL SURFACE     │
               │  ExecutionTreeNode     │
               │  VariableEngine        │
               │  ExecutablePlugin      │
               │  ExecutionMode         │
               │  PluginRegistry        │
               │  PluginExecutor        │
               │  Features + Context    │
               └───────────┬────────────┘
                           │
           ----------------------------------------
           │            │             │           │
        Plugins     (Future) CM   (Future) SM  (Events/Ledger)
```

- The **kernel surface** is mostly in:
  - `olo-worker-execution-tree`
  - `olo-worker-execution-context`
  - `olo-worker-features`
  - `olo-worker-plugin`
  - `olo-worker-protocol`
- Temporal, HTTP, OpenAI, DBs, planners, and UI live **outside** this surface and call into it.

As the connection manager, secret manager, and event system are implemented, they should plug into this kernel surface via interfaces, without expanding the kernel’s core responsibility set.

