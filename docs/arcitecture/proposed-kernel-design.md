# Olo Kernel — Proposed Microkernel Design

This document describes a **proposed** future architecture for Olo based on a **microkernel** design. It is a target structure to guide refactors and new modules, not a description of the current code (see `current-kernel-design.md` for today’s layout).

---

## 1. Core Principle: Microkernel Architecture

The **kernel** contains only what must never change: small, stable contracts and minimal runtime types. Everything else becomes modules or plugins.

High-level view:

```
               +------------------------+
               |        OLO API         |
               +-----------+------------+
                           |
               +-----------v------------+
               |       OLO KERNEL       |
               |------------------------|
               | Execution Engine       |
               | Node Runtime           |
               | Variable Context       |
               | Feature Runtime        |
               | Event Bus              |
               +-----------+------------+
                           |
         --------------------------------------------
         |            |             |                |
    Plugins      Connections      Secrets       Features
```

The kernel stays small, but capabilities are unbounded via plugins, connections, secrets, and features.

---

## 2. Proposed Module Structure

Target multi-module layout (`olo` is the top-level project):

```
olo
│
├── olo-kernel
│
├── olo-execution
├── olo-node-types
├── olo-feature-runtime
├── olo-plugin-runtime
├── olo-connection-manager
├── olo-secret-manager
│
├── olo-runtime-temporal
├── olo-runtime-local
│
├── olo-event-system
├── olo-planner
│
├── olo-api
├── olo-sdk
│
└── olo-plugins
```

Each module has a focused responsibility and clear dependency direction (see §17).

---

## 3. `olo-kernel` (THE HEART)

This module must remain **extremely stable**. It contains only **core contracts** and minimal runtime types:

- **Execution contracts**
  - `ExecutionContext`
  - `Node`
  - `NodeExecutor`
  - `ExecutionResult`
- **Data / variables**
  - `VariableStore` / variable context contract
- **Cross-cutting**
  - `Feature` (feature contracts)
  - `Event` (execution / node / plugin events)
- **Capabilities / resources**
  - `Plugin` (execution plugin interface)
  - `Connection` (logical connection ref)
  - `SecretRef` (logical secret reference)
- **Service registry**
  - `OloServiceRegistry` (see §19).

Example core contract:

```java
public interface NodeExecutor {

    ExecutionResult execute(
        Node node,
        ExecutionContext context
    );
}
```

**Kernel does NOT know (by design):**

- OpenAI / Anthropic
- SQL / Postgres
- Kafka / Redis
- HTTP clients
- Temporal

Those concerns live in higher-level modules that depend on the kernel, not the other way around.

---

## 4. `olo-execution` (Execution Runtime)

Implements the **execution tree runtime** using the kernel contracts.

Responsibilities:

- `ExecutionTreeRunner` – entry point: run a pipeline from a root node.
- `NodeDispatcher` – dispatch by node type (`NodeType`).
- `BranchExecutor` – IF / SWITCH / TRY-CATCH branches.
- `LoopExecutor` – ITERATOR / LOOP / FORK-JOIN patterns.
- `ErrorHandler` – error propagation, retries (in kernel space).
- `VariableResolver` – resolves variable mappings per node.

Example flow:

```
ExecutionTreeRunner
       ↓
NodeDispatcher
       ↓
NodeExecutor (plugin node or built-in node type)
```

This module **runs every pipeline** regardless of the underlying runtime (Temporal, local, etc.).

---

## 5. `olo-node-types` (Built-in Nodes)

Contains built-in node types and their executors.

Examples:

- `if`
- `loop`
- `parallel` / `fork` / `join`
- `switch`
- `assign`
- `log`
- `wait`
- `human_step`
- `plugin_call`

Why separate?

- Allows different node-type bundles over time:
  - `olo-node-types-basic`
  - `olo-node-types-ai`
  - `olo-node-types-enterprise`

The execution runtime (`olo-execution`) depends only on the `Node` + `NodeExecutor` contracts; node-type modules implement those contracts.

---

## 6. `olo-feature-runtime` (Feature Pipeline)

Executes cross-cutting features around node execution.

Example features:

- Logging
- Metrics
- Quota
- Ledger
- Execution events
- Audit
- Retry policies

Execution shape:

```
FeatureBefore
    ↓
NodeExecution
    ↓
FeatureAfter
```

Very similar to middleware or interceptors: features inspect/augment context before and after node execution.

---

## 7. `olo-plugin-runtime` (Plugin Infrastructure)

Handles the **lifecycle and discovery** of plugins.

Responsibilities:

- `PluginLoader` – load plugin modules (internal + external).
- `PluginRegistry` – map plugin id → plugin/provider.
- `PluginClassLoader` – isolate community plugins.
- `PluginValidator` – validate manifests and contracts.
- `PluginManifestReader` – read plugin descriptors (id, capabilities, versions).

Plugin kinds:

- Tool plugins
- Model plugins
- Datasource plugins
- Connector plugins

This module should be **framework-neutral** (no Spring, no Temporal) so it can be reused by different runtimes.

---

## 8. `olo-connection-manager` (Resource/Connection Runtime)

Handles:

- Connection configs (per tenant).
- Tenant isolation.
- Secret resolution for connections.
- Binding connections to plugins and runtimes.

Example:

```text
connection: openai-prod
plugin:     openai
model:      gpt4
secret:     openai_api_key
```

At runtime:

```text
plugin + connection config
        ↓
resolved client (ModelClient, VectorClient, ToolClient, …)
```

This is the concrete implementation of the `ResourceRuntimeManager` / `ConnectionRuntimeManager` concept described in `connection-manager-design.md`.

---

## 9. `olo-secret-manager` (Secret System)

Pluggable secret providers behind a stable kernel contract.

Example providers:

- `env` (environment variables)
- `hashicorp-vault`
- `aws-secretsmanager`
- `azure-keyvault`
- `gcp-secret-manager`

Pipelines reference secrets by logical ref:

```text
secret://openai/api_key
```

The secret manager resolves `SecretRef` using configured providers, with multi-tenant isolation and audit logging.

---

## 10. `olo-event-system` (Events & Streaming)

Provides a generic **event bus** for:

- UI live updates
- Execution logs
- Human steps (human-in-the-loop)
- Planner progress
- Metrics
- Debugging

Example events:

- `NodeStarted`
- `NodeCompleted`
- `NodeFailed`
- `HumanInputRequested`
- `PlannerStep`
- `PluginCall`

Transports (in adapter submodules):

- WebSocket
- Kafka
- Redis
- gRPC stream

Kernel and execution emit structured events; transport modules deliver them to UIs and observability systems.

---

## 11. `olo-runtime-temporal` (Temporal Adapter)

Distributed runtime built on Temporal.

Responsibilities:

- `TemporalWorkflowAdapter` – wraps kernel `ExecutionTreeRunner` inside a Temporal workflow.
- `TemporalActivityExecutor` – runs node execution / plugin calls inside activities where needed.
- Retry integration – map kernel retry policies to Temporal retry options.
- Persistence / long-running flows – delegate to Temporal’s durable workflow model.

Kernel **must not depend on Temporal**. This module adapts kernel/execution to Temporal, not the other way around.

---

## 12. `olo-runtime-local` (Local Runner)

Local, in-process runtime for tests and development.

- Runs the execution engine **without Temporal**.
- Great for:
  - Fast unit/integration tests.
  - Debugging pipeline behavior locally.
  - CLI or small-server use cases.

It reuses `olo-execution` and kernel contracts; the only difference is how runs are started and observed.

---

## 13. `olo-planner` (AI Planning)

AI planning module.

Responsibilities:

- Plan generator – uses LLMs and heuristics to produce a plan.
- Plan executor – runs planner nodes within the execution tree.
- Plan validator – ensures outputs are well-formed and safe.
- LLM prompt templates – shared templates for planning prompts.

Planner outputs:

- An **Execution Tree** (or subtree), expressed using kernel `Node` / `ExecutionTreeNode` contracts.

---

## 14. `olo-api` (HTTP / WebSocket)

Public API for applications and UI.

Example endpoints:

- `POST /pipeline/run`
- `GET /execution/{id}`
- `POST /human/response`

The UI talks only to `olo-api`. This module depends on:

- `olo-execution` / runtimes (Temporal/local)
- `olo-event-system`
- `olo-kernel` contracts

---

## 15. `olo-sdk` (Developer SDK)

For developers writing:

- Plugins
- Features
- Node types

Contains:

- Plugin interfaces (wrappers over `ExecutablePlugin` and related contracts)
- Feature interfaces
- NodeExecutor interfaces
- Connection / secret annotations or helper builders

The SDK is a **friendly façade** over `olo-kernel` contracts, without exposing internal worker details.

---

## 16. `olo-plugins` (External Plugin Repository)

Concrete plugin implementations:

- `plugin-openai`
- `plugin-anthropic`
- `plugin-postgres`
- `plugin-kafka`
- `plugin-http`
- `plugin-vector-db`

These are **not kernel modules**. They depend on:

- `olo-sdk`
- `olo-kernel`

They must not depend on `olo-execution`, `olo-runtime-temporal`, or `olo-api`.

---

## 17. Critical Rule: Dependency Direction

Dependency flow must always go **downwards** towards the kernel:

```text
olo-api
   ↓
olo-execution (and runtimes)
   ↓
olo-kernel
```

Plugins and external modules depend only on:

- `olo-sdk`
- `olo-kernel`

**Never the other way around.**

This avoids tight coupling and makes it possible to introduce new runtimes (e.g. non-Temporal) or UIs without touching the kernel.

---

## 18. From Current Layout to Modules

Today the “kernel” is **implicit** and spread across:

- `olo-worker-execution-tree`
- `olo-worker-execution-context`
- `olo-worker-features`
- `olo-worker-plugin`
- `olo-worker-protocol`

and execution logic lives mostly in `olo-worker` (Temporal worker). To make the boundary explicit:

- **Create `olo-kernel`** and move only contracts + minimal types:
  - `ExecutionTreeNode` / `NodeType` (or their kernel equivalents).
  - `ExecutionContext` / `VariableStore` contracts.
  - `ExecutablePlugin` / `ExecutionMode`.
  - Feature and event interfaces.
  - `ConnectionRef`, `ConnectionResolver`, `SecretRef`, `SecretResolver` (contracts only).
  - `OloServiceRegistry`.
- **Create `olo-execution`** and move execution logic out of `olo-worker`:
  - `ExecutionEngine`, `NodeDispatcher`, `VariableEngine`, feature runner, node executors.
  - Temporal then calls into `ExecutionEngine` instead of owning execution.
- **Create `olo-plugin-runtime`** and centralize plugin infrastructure:
  - `PluginRegistry`, `PluginLoader`, `PluginProvider`, `PluginExecutor`, plugin manifest handling.
  - Kernel depends only on `ExecutablePlugin`; runtimes depend on `PluginRegistry` via `OloServiceRegistry`.
- **Introduce `olo-event-system`** as a first-class module:
  - Kernel contracts: `ExecutionEvent`, `EventPublisher`.
  - Transports (Kafka/WebSocket/Redis/gRPC) as adapters.
- **Add `olo-sdk`** for plugin/feature/node-type authors:
  - Re-export kernel contracts in a stable developer API.
  - Plugins depend on `olo-sdk` + `olo-kernel`, never on worker internals.
- **Add `olo-testing`** for local runs and assertions:
  - Pipeline test runner, mock plugins/connections, execution assertions.

Suggested top-level projects (names illustrative):

```text
olo
 ├── kernel
 ├── execution
 ├── feature-runtime
 ├── plugin-runtime
 ├── connection-manager
 ├── secret-manager
 ├── event-system
 ├── runtime-temporal
 ├── runtime-local
 ├── node-types
 ├── planner
 ├── api
 └── sdk
```

`olo-plugins` lives as a separate directory or repo that builds against `kernel` + `sdk`.

---

## 19. Service Registry in the Kernel

To avoid dependency injection tangles, the kernel should expose a small **core service registry**:

```java
public interface OloServiceRegistry {

    <T> void register(Class<T> type, T service);

    <T> T get(Class<T> type);
}
```

Runtime services typically registered:

- `PluginRegistry`
- `FeatureRegistry`
- `ConnectionResolver` / `ResourceRuntimeManager`
- `SecretResolver`
- `EventPublisher`
- `ExecutionEngine`

**Formal role:**

- `OloServiceRegistry` is the **single entry point** for registering and resolving services inside the kernel and runtimes.
- Kernel and execution code depend only on **service interfaces** (e.g. `ConnectionResolver`, `EventPublisher`) and the registry, not on concrete implementations or DI frameworks.

Bootstrap registers services once:

```java
OloServiceRegistry services = new DefaultOloServiceRegistry();
services.register(PluginRegistry.class, pluginRegistry);
services.register(FeatureRegistry.class, featureRegistry);
services.register(ConnectionResolver.class, connectionResolver);
services.register(SecretResolver.class, secretResolver);
services.register(EventPublisher.class, eventPublisher);
services.register(ExecutionEngine.class, executionEngine);
```

Runtime components access services via the registry, for example through `ExecutionContext`:

```java
ConnectionResolver connections = ctx.services().get(ConnectionResolver.class);
PluginRegistry plugins = ctx.services().get(PluginRegistry.class);
```

This prevents each module from inventing its own injection pattern and keeps dependency wiring centralized, explicit, and testable.

---

## 20. Golden Rule and Testing Module

**Golden rule:** Olo kernel should stay around **10–15 core interfaces**. If it grows beyond that, responsibilities are leaking into the kernel.

Recommended extra module:

- **`olo-testing`**
  - Pipeline test runner (local, non-Temporal).
  - Mock plugins and connections.
  - Execution assertions.

Example test style:

```java
pipelineTest()
   .withPlugin("openai", mock)
   .run(pipeline)
   .assertOutput("answer", "hello");
```

This keeps the kernel small, the runtime flexible, and the developer experience strong as Olo grows.

