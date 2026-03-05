# Olo Bootstrap Architecture

This document describes how the Olo worker **bootstraps** itself: where plugins and features are registered, how configuration is loaded, and how the runtime is wired together before Temporal (or a local runtime) starts executing pipelines.

The goal is to keep bootstrap logic:

- **Explicit** – easy to follow for contributors.
- **Modular** – new plugins/features/runtimes can be added without touching core logic.
- **Aligned with the microkernel design** – kernel contracts, runtimes, and plugins are wired together in a clear, one-way dependency graph.

---

## 1. High-Level Bootstrap Flow

At a high level, worker startup looks like:

```text
OloWorkerApplication
      ↓
OloBootstrap.initializeWorker()
      ↓
WorkerBootstrapContext
      ↓
Temporal workers / runtimes start using the configured context
```

Key outcomes of bootstrap:

- Plugins and tools are registered and wired via `PluginRegistry` / `PluginExecutorFactory`.
- Features are registered and attached via `FeatureRegistry` / `PipelineFeatureContext`.
- Execution trees and tenant configs are loaded and snapshotted.
- Connection and secret resolvers (when implemented) are registered.
- An `EventBus` / `ExecutionEventBus` is registered so logging, metrics, UI updates, human-approval steps, and other features can emit structured events.
- A shutdown hook (`ResourceCleanup.onExit()` etc.) is configured.

---

## 1.1 Bootstrap Phases

As Olo grows, it is useful to treat bootstrap as a sequence of **phases** rather than one opaque `initializeWorker()` call. A phased model makes ordering, validation, and extension points explicit.

### Bootstrap Phases (Summary)

1. **CORE_SERVICES** – set up core infrastructure (logging, config, basic registries).
2. **PLUGIN_DISCOVERY** – discover plugin descriptors and populate `PluginRegistry`.
3. **FEATURE_DISCOVERY** – discover feature descriptors and populate `FeatureRegistry`.
4. **RESOURCE_PROVIDERS** – wire connection, secret, and event providers.
5. **PIPELINE_LOADING** – load and validate pipelines, build execution trees and snapshots.
6. **WORKER_START** – start Temporal/local workers using the fully-wired context.

Each module can register a `BootstrapContributor` to participate in one or more phases (see §1.2).

Detailed phase behavior:

### Phase 1 — Environment & Config Load

- Resolve environment (paths, plugin directories, tenants, queues).
- Load **static config**:
  - Tenant list.
  - Pipeline definitions.
  - Global settings (timeouts, limits, feature flags).
- Build `ExecutionConfigSnapshot` per tenant/queue (immutable snapshot for each run).

### Phase 2 — Descriptor Discovery

- Scan plugin/feature/connection directories or classpath for descriptors:
  - `META-INF/olo-plugin.json` (per plugin JAR; serialized `PluginDescriptor`).
  - `META-INF/olo-features.json` (per feature JAR, optional).
  - Optional shared catalogs such as `META-INF/olo-connections.json`.
- Parse descriptors into in-memory models:
  - `PluginDescriptor`
  - Feature descriptors
  - Connection schemas.

### Phase 3 — Registry Wiring

- Instantiate and register runtime services:
  - `PluginRegistry` (singleton plugin instances per worker).
  - `FeatureRegistry`.
  - (Future) `ConnectionRegistry` / `ResourceRuntimeManager`.
  - (Future) `SecretResolver`, event system adapters.
- Bind factories and executors:
  - `PluginExecutorFactory` → `RegistryPluginExecutor` → `PluginRegistry`.
  - Feature attachment enrichers and contexts.

### Phase 4 — Validation

- Validate that:
  - All `pluginRef` in execution trees resolve to registered plugins.
  - All connection refs resolve to known `connectionType` + schema.
  - Capabilities, `executionMode`, and node-level overrides are allowed for each plugin.
  - Feature attachments are consistent with node types.
- Fail fast if any inconsistency is found (no Temporal workers are started).

### Phase 5 — Context Construction

- Build `WorkerBootstrapContext` (or future `OloServiceRegistry` view) exposing only what runtimes need:
  - `PluginExecutorFactory`
  - `PipelineFeatureContext`
  - Connection/secret/event services
  - `runResourceCleanup()` hook.

### Phase 6 — Runtime Start

- Start Temporal workers (or a local runtime) using the constructed context.
- After this point, **no further discovery or wiring** occurs; runtime components only:
  - Look up descriptors and registries populated during bootstrap.
  - Make direct method calls (plugins, features, resolvers).

---

## 1.2 Bootstrap Phase Model (Extensibility)

To keep bootstrap extensible without chaos, phases can be represented explicitly in code via a `BootstrapPhase` enum and `BootstrapContributor` extension point.

Example phases:

```java
public enum BootstrapPhase {
    CORE_SERVICES,
    PLUGIN_DISCOVERY,
    FEATURE_DISCOVERY,
    RESOURCE_PROVIDERS,   // connections, secrets, events
    PIPELINE_LOADING,
    WORKER_START
}
```

Extension point:

```java
public interface BootstrapContributor {
    void contribute(BootstrapPhase phase, BootstrapContext context);
}
```

`OloBootstrap` can then drive bootstrap as:

```java
for (BootstrapPhase phase : BootstrapPhase.values()) {
    for (BootstrapContributor c : contributors) {
        c.contribute(phase, context);
    }
}
```

Concrete contributors can live in separate modules, for example:

- `PluginBootstrapContributor` – handles plugin descriptor loading and `PluginRegistry` registration.
- `FeatureBootstrapContributor` – registers features and feature contexts.
- `ConnectionBootstrapContributor` – wires connection and secret managers.

This allows:

- Adding new bootstrap logic (e.g. new plugin/feature packs, new resource providers) without modifying core bootstrap.
- Clear ordering and isolation per phase (e.g. all core services before discovery, all discovery before pipeline loading).

---

## 1.3 Bootstrap-Time Binding Principle

Olo deliberately avoids **runtime reflection-based discovery**. All plugins, features, and runtime services are **bound during worker bootstrap**, not at call time.

Binding pipeline:

```text
annotations → descriptor (e.g. META-INF/olo-plugin.json)
        ↓
bootstrap binding (OloBootstrap / loaders)
        ↓
runtime registries (PluginRegistry, FeatureRegistry, ConnectionResolver, SecretResolver, …)
```

This ensures:

- Fast worker startup (no classpath scanning loops).
- Deterministic wiring (registries are fully populated before any run starts).
- Predictable plugin behavior (no mid-run discovery or late binding).

---

## 2. Modules Involved in Bootstrap

Current modules that participate in bootstrap:

- **`olo-worker-bootstrap`**
  - `OloBootstrap` – core bootstrap logic.
  - `WorkerBootstrapContext` – contract exposing the wired services to the worker.
  - `PipelineDynamicNodeBuilder`, `PipelineNodeFeatureEnricher` – helper classes for planner/dynamic nodes.
- **`olo-internal-plugins` / `olo-internal-tools`**
  - Register built-in plugins and tools.
- **`olo-worker-plugin`**
  - `PluginRegistry`, `PluginExecutorFactory`, `RegistryPluginExecutor`.
- **`olo-worker-features` / feature modules**
  - Register built-in features (logging, metrics, quota, ledger, debug).
- **`olo-worker-execution-tree` / `olo-worker-configuration`**
  - Load pipeline configuration and build execution trees per tenant/queue.

Over time, this wiring will move towards the proposed modules (`olo-kernel`, `olo-execution`, `olo-plugin-runtime`, `olo-feature-runtime`, `olo-connection-manager`, `olo-secret-manager`), but the **bootstrap responsibilities** remain the same.

---

## 3. `OloBootstrap` Responsibilities

`OloBootstrap` should have a narrow, well-defined set of responsibilities:

- **Discover and load configuration**
  - Load tenant list and pipeline configuration (from Redis/DB/file/env, depending on deployment).
  - Build `ExecutionConfigSnapshot` per tenant/queue.
- **Initialize registries**
  - Construct and populate:
    - `PluginRegistry` (plugins, tools).
    - `FeatureRegistry`.
    - (Future) `ConnectionRegistry` / `ResourceRuntimeManager`.
    - (Future) `SecretResolver` and event system adapters.
- **Run bootstrap contributors**
  - Call extension points (e.g. planners, custom registries) that participate in startup.
- **Expose a `WorkerBootstrapContext`**
  - So `olo-worker` can start Temporal workers without needing to know how plugins/features were registered.

Conceptually:

```text
OloBootstrap.initializeWorker()
    ↓
load tenant + config
    ↓
register plugins/tools
    ↓
register features
    ↓
build WorkerBootstrapContext
```

---

## 4. `WorkerBootstrapContext` (Runtime View)

`WorkerBootstrapContext` is the **bridge** between bootstrap and the runtime:

- It exposes **interfaces**, not implementation details:
  - `PluginExecutorFactory` – so the worker can obtain `PluginExecutor` instances without depending on `PluginRegistry` directly.
  - `PipelineFeatureContext` – to attach pipeline/queue-scoped features.
  - `runResourceCleanup()` – shutdown lifecycle hook.
  - (Future) an `OloServiceRegistry` view so all modules resolve services (plugins, features, connections, secrets, events) through a single registry interface instead of ad-hoc dependency injection.

At runtime, the worker does something like:

```text
OloBootstrap.initializeWorker()
      ↓
WorkerBootstrapContext ctx
      ↓
create Temporal workers using ctx.getPluginExecutorFactory(), ctx.getFeatureContext(), …
```

The worker never sees annotation processors, JSON descriptors, or plugin loading details; it depends only on the contracts in `WorkerBootstrapContext`.

Over time, to avoid `WorkerBootstrapContext` becoming a “god context” that exposes too many concerns, it can hand back a smaller **`WorkerRuntime`** façade that groups related capabilities:

```java
public interface WorkerRuntime {
    PluginExecutorFactory plugins();
    FeatureRuntime features();
    EventBus events();
    // plus: connections(), secrets(), etc. when those runtimes exist
}
```

Bootstrap builds `WorkerRuntime` from the underlying registries and services, and the worker uses only this runtime façade. This keeps long-term separation of concerns cleaner while still centralizing wiring in bootstrap.

### 4.1 End-to-End Bootstrap & Runtime Diagram

The overall wiring from application start to execution looks like:

```text
                OloWorkerApplication
                         │
                         ▼
                    OloBootstrap
                         │
                         ▼
                ┌─────────────────┐
                │ ServiceRegistry │  (OloServiceRegistry)
                └─────────────────┘
                     │     │
                     │     │
        ┌────────────┘     └─────────────┐
        ▼                                ▼
   PluginRuntime                    FeatureRuntime
        │                                │
        ▼                                ▼
   PluginRegistry                   FeatureRegistry
        │                                │
        └───────────────┬────────────────┘
                        ▼
                WorkerBootstrapContext / WorkerRuntime
                        │
                        ▼
                Temporal Worker (olo-runtime-temporal)
                        │
                        ▼
                Execution Engine (olo-execution)
```

This diagram shows:

- **OloBootstrap** wiring all services into a central `OloServiceRegistry`.
- Plugin and feature runtimes building `PluginRegistry` and `FeatureRegistry` on top of that.
- `WorkerBootstrapContext` / `WorkerRuntime` exposing a minimal façade to the Temporal worker.
- The Temporal worker delegating execution to the **Execution Engine**, which uses registries and services via the service registry.

---

## 5. Plugin & Feature Registration (Current Pattern)

### 5.1 Plugins

Today:

- Internal plugins are registered in `olo-internal-plugins`:
  - They use `PluginRegistry.getInstance().register…(tenantId, id, plugin)` to register:
    - `ModelExecutorPlugin` (LLM/chat).
    - `EmbeddingPlugin`.
    - `VectorStorePlugin`.
    - `ImageGenerationPlugin`.
- `RegistryPluginExecutor` in `olo-worker-plugin` implements `PluginExecutor`:
  - Resolves plugins from `PluginRegistry`.
  - Invokes `ExecutablePlugin.execute(inputs, tenantConfig)`.
- `DefaultPluginExecutorFactory` returns `RegistryPluginExecutor` instances given a tenant id and a node instance cache.

**Executor binding:** Bootstrap wires a single `PluginExecutorFactory` into `WorkerBootstrapContext` so the worker depends only on that factory, not on `PluginRegistry`:

```text
PluginExecutorFactory
     ↓
RegistryPluginExecutor
     ↓
PluginRegistry
```

At runtime the worker simply calls:

```java
PluginExecutor executor = executorFactory.create(nodeContext);
String outputsJson = executor.execute(pluginId, inputsJson, nodeId);
```

All registry lookups and plugin invocation are hidden behind this factory + executor chain.

### 5.2 Features

- Features are registered via `FeatureRegistry` (internal feature modules).
- `PipelineNodeFeatureEnricher` attaches:
  - Queue/pipeline features.
  - Node-level feature overrides.
- `NodeExecutionContext` is created per node, and feature chains run:
  - `before(NodeExecutionContext)`
  - `afterSuccess` / `afterError`
  - `afterFinally`

Bootstrap ensures `FeatureRegistry` and `PipelineFeatureContext` are ready before any pipeline executes.

---

## 6. Event System Wiring

The event system is a first-class runtime service that needs explicit wiring at bootstrap time.

- **Service:** `ExecutionEventBus` (or generic `EventBus` abstraction).
- **Producers:** features (logging, metrics, quota, ledger, execution-events), node handlers, planner, human-step handlers.
- **Consumers:** UI/WebSocket adapters, metrics systems, audit logs, debugging tools.

Bootstrap responsibilities:

- Construct and register the event bus (or adapter to an external bus) in the core service registry (`OloServiceRegistry`), e.g.:

  ```java
  services.register(EventBus.class, executionEventBus);
  ```

- Ensure features and runtimes that emit events receive an `EventBus` reference via context or service registry.

At runtime, components use:

```java
EventBus events = ctx.services().get(EventBus.class);
events.publish(new NodeStartedEvent(...));
```

to emit structured events for UI, observability, and human-in-the-loop workflows.

---

## 7. Descriptor-Driven Plugin Bootstrap (Future)

As the annotation module (`olo-annotations`) and descriptors (`META-INF/olo-plugin.json`) mature, plugin bootstrap should evolve to:

```text
PluginLoader (in olo-plugin-runtime)
    ↓
scan plugin classpath / plugin directory
    ↓
locate META-INF/olo-plugin.json
    ↓
deserialize PluginDescriptor
    ↓
register in PluginRegistry
```

- No reflection scanning.
- `PluginDescriptor` is the only runtime source of plugin metadata.
- `OloBootstrap` delegates discovery/registration to `olo-plugin-runtime`, and focuses only on wiring the resulting `PluginRegistry` into `WorkerBootstrapContext`.

---

## 8. Towards the Microkernel Layout

In the proposed microkernel architecture:

- `olo-kernel` defines:
  - `ExecutablePlugin`, `PluginDescriptor`, `ExecutionContext`, `ExecutionMode`, `Feature`, events, connection/secret contracts.
- `olo-plugin-runtime` owns plugin discovery and registration.
- `olo-feature-runtime` owns feature resolution and execution.
- `olo-execution` owns the execution engine.
- `olo-runtime-temporal` (today’s `olo-worker`) adapts execution to Temporal.
- `olo-annotations` is used only at compile time to generate descriptors.

**Bootstrap** becomes a thin orchestrator that:

- Wires the kernel contracts together (via `OloServiceRegistry` or equivalent).
- Calls into plugin/feature/connection/secret runtimes to perform their own discovery.
- Builds a small `WorkerBootstrapContext` exposing only what the runtime needs.

This keeps startup logic clear, testable, and aligned with the microkernel goals: small kernel, powerful extensions.

---

## 9. Key Rule: All Wiring Happens at Bootstrap

**Rule:** All runtime wiring happens during **bootstrap**. Runtime components **must not** perform discovery or scanning.

Allowed during runtime:

- Descriptor lookup (e.g. reading `META-INF/olo-plugin.json` once at startup, not per-call).
- Registry lookup (e.g. `PluginRegistry.get(pluginId)`).
- Direct method calls (e.g. `plugin.execute(...)`, `feature.before(...)`, `connectionResolver.get(...)`).

Not allowed during runtime:

- Classpath / annotation scanning.
- Ad-hoc plugin or feature discovery.
- Dynamic loading that bypasses the bootstrap contracts.

This keeps the runtime fast, predictable, and aligned with the microkernel pattern: discovery and binding are explicit, one-time concerns of `OloBootstrap` and the runtime-specific loaders.

