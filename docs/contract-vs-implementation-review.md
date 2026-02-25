# Contract vs Implementation: Worker and Kernel Protocol

## Principles

1. **Worker depends on contract only** — The worker (kernel) must not depend on concrete plugin or feature implementations. It depends on interfaces/protocols (e.g. `ExecutablePlugin`, `SubtreeBuilder`, `PreNodeCall`) and on **pluggable suppliers** (e.g. a factory that provides a plugin executor) so that any implementation that follows the protocol can be adopted.

2. **Kernel adopts any plugin/feature following the protocol** — The kernel is capable of running any plugin or feature that implements the published contract and is registered (or supplied) at bootstrap. The kernel does not import concrete plugin/feature classes (e.g. `OllamaModelExecutorPlugin`, `QuotaFeature`); those are wired in by bootstrap or by the application.

---

## Current State (Audit)

### What is already correct

| Area | Detail |
|------|--------|
| **Planner** | Worker depends only on **olo-planner** (contract): `SubtreeBuilder`, `SubtreeBuilderRegistry`, `PromptTemplateProvider`, `PlannerContract`. Implementations (e.g. **olo-planner-a**) register at bootstrap; worker never imports `olo-planner-a`. |
| **Plugin execution path** | `PluginInvoker` and the execution engine use only a **PluginExecutor** callback (execute, toJson, fromJson). They do not reference `PluginRegistry` or concrete plugins. |
| **Features (phase contracts)** | Worker uses interfaces: `PreNodeCall`, `PostSuccessCall`, `PostErrorCall`, `FinallyCall`, `PreFinallyCall`, `NodeExecutionContext`, `ResolvedPrePost`. Implementations (e.g. `QuotaFeature`, `DebuggerFeature`) are registered by **bootstrap** (e.g. `InternalFeatures`); worker does not import those classes. |
| **Bootstrap** | **olo-worker-bootstrap** depends on concrete modules (olo-planner-a, olo-internal-plugins, olo-internal-features) and wires implementations into registries. Worker does not depend on olo-planner-a. |

### What was wrong (and is being fixed)

| Area | Issue | Fix |
|------|--------|-----|
| **Plugin resolution in activity** | `OloKernelActivitiesImpl` called `PluginRegistry.getInstance().getExecutable(...)` directly. So the **worker** module depended on the concrete **PluginRegistry** and **ExecutablePlugin** usage in the activity. | Introduce **PluginExecutorFactory** (contract in protocol). Activity receives the factory from bootstrap and uses `factory.create(tenantId, cache)` to obtain a **PluginExecutor**. The implementation that uses `PluginRegistry` lives in **olo-worker-plugin** and is wired in by bootstrap. Worker no longer depends on olo-worker-plugin. |
| **Shutdown / resource cleanup** | `OloWorkerApplication.invokeResourceCleanup()` used `PluginRegistry.getInstance()` and `FeatureRegistry.getInstance()` directly, so the worker application depended on those concrete registries. | Add **runResourceCleanup()** to **WorkerBootstrapContext**. Bootstrap implementation iterates plugins and features and invokes `ResourceCleanup.onExit()`. Worker application only calls `ctx.runResourceCleanup()` and no longer imports `PluginRegistry` or `FeatureRegistry` for cleanup. |

### Optional future improvements (features)

- **Feature resolution**: Worker could depend on an interface **FeatureResolver** / **FeatureRunner** instead of the concrete **FeatureRegistry**. The implementation that uses `FeatureRegistry` would live in olo-worker-features and be injected at bootstrap. Same pattern as plugin executor factory.

---

## Protocol and Dependency Direction

```
┌─────────────────────────────────────────────────────────────────┐
│  olo-worker-protocol (contracts only)                           │
│  BootstrapContext, WorkerBootstrapContext, BootstrapContributor │
│  PluginExecutor, PluginExecutorFactory, runResourceCleanup()     │
└─────────────────────────────────────────────────────────────────┘
         ▲                                    ▲
         │                                    │
┌────────┴────────┐                 ┌────────┴────────┐
│  olo-worker     │                 │ olo-worker-     │
│  (kernel)       │                 │ bootstrap       │
│  No plugin/     │                 │ Wires plugin    │
│  feature impl   │                 │ and feature     │
│  imports        │                 │ implementations │
└────────┬────────┘                 └────────┬────────┘
         │                                    │
         │                           ┌────────┴────────┐
         │                           │ olo-worker-     │
         │                           │ plugin          │
         │                           │ Implements      │
         └──────────────────────────►│ PluginExecutor  │
           depends on contract       │ Factory,        │
           (PluginExecutor from      │ uses            │
           protocol)                 │ PluginRegistry  │
                                     └─────────────────┘
```

- **Worker** depends on **protocol** (and execution-tree, features contract, planner contract, etc.), not on **olo-worker-plugin**.
- **Bootstrap** depends on plugin, planner-a, internal features, etc., and supplies **PluginExecutorFactory** and **runResourceCleanup** in the worker context.
- **Kernel** can adopt any plugin/feature that follows the protocol and is registered or supplied at bootstrap.

---

## File-Level Summary

| Component | Depends on (contract) | Does not depend on |
|-----------|------------------------|--------------------|
| olo-worker (kernel) | olo-worker-protocol (PluginExecutor, PluginExecutorFactory), olo-planner (SubtreeBuilder, etc.), olo-worker-features (phase interfaces, ResolvedPrePost), olo-worker-execution-tree, etc. | olo-worker-plugin, olo-planner-a, concrete plugin/feature classes |
| olo-worker-bootstrap | olo-worker-protocol, olo-worker-plugin (to create DefaultPluginExecutorFactory), PluginRegistry, FeatureRegistry, olo-planner-a, internal features/plugins | — |
| olo-worker-plugin | olo-worker-protocol (implements PluginExecutor, PluginExecutorFactory), PluginRegistry, ExecutablePlugin | — |

---

## How to Add a New Plugin or Feature

1. **Plugin**: Implement the contract (e.g. `ModelExecutorPlugin` extends `ExecutablePlugin`). Register via `PluginProvider` and ensure the provider is included in bootstrap (e.g. internal or community JAR). The kernel will execute it via `PluginExecutor.execute(pluginId, ...)`; the implementation of `PluginExecutor` resolves the plugin from the registry. No change in the worker module.

2. **Feature**: Implement the phase contract(s) (e.g. `PreNodeCall`, `FinallyCall`). Register with `FeatureRegistry` at bootstrap (e.g. via `InternalFeatures` or a contributor). The kernel resolves and runs features via `FeatureRegistry` (or a future FeatureResolver interface). No change in the worker module.

3. **Planner parser**: Implement `SubtreeBuilder`. Register with `SubtreeBuilderRegistry.setResolver(...)` at bootstrap (e.g. from olo-planner-a). The kernel calls `SubtreeBuilderRegistry.get(name)` and uses the returned builder. No change in the worker module.

The kernel remains unchanged; only bootstrap and the new plugin/feature/parser module are updated.
