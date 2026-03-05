# Olo Plugin Design — Specification

This document defines the **plugin architecture** for Olo: what a plugin is, how it is registered and discovered, how it integrates with the **execution tree** (today) and the **Connection Manager** (design), and what contract plugin authors must implement. It aligns with the other [architecture docs](#related-architecture-docs) in this folder and with the existing [creating-plugins-and-features](../creating-plugins-and-features.md) guide.

---

## Introduce a PluginDescriptor

**Introduce a PluginDescriptor** from day one. It unlocks:

- **Plugin marketplace** — List and install plugins by id, vendor, version, capabilities.
- **Plugin UI** — Show displayName, vendor, capabilities, and schema-driven connection forms.
- **Capability discovery** — Resolve plugins by capability (e.g. “chat”, “embedding”) for pipeline validation and discovery.
- **Compatibility checks** — contractVersion and dependencies enable config and dependency validation.

**Without it, things become messy later.** Ad-hoc metadata, duplicate registries, and no clear way to drive UI or marketplace. This doc defines **PluginDescriptor** (id, displayName, vendor, version, type, contractVersion, capabilities, dependencies) as the single source of plugin identity and metadata.

---

## Goals

- **Pluggable capabilities** — Model execution, vector stores, tools, storage, etc. are provided by plugins; the worker and Connection Manager do not depend on concrete implementations.
- **Single registry** — Plugins are registered in a **PluginRegistry**. Recommended: **global** registry (plugins are code, not data); **connections** are tenant-scoped (ConnectionRegistry per tenant). See §PluginRegistry.
- **Typed runtimes** — When used with the Connection Manager, plugins return **typed clients** (ModelClient, VectorClient, ToolClient, StorageClient), not `Object`.
- **Schema-driven UX** — Plugins declare **ConnectionSchema** for UI forms, validation, and docs.
- **Thread safety** — Cached runtimes (Connection Manager) are reused across activities and concurrent runs; plugin runtimes must be thread-safe when cached.

---

## Risks and future-proofing

The following are not problems today but will matter as Olo grows. The design below addresses them.

| Risk | Issue | Direction in this doc |
|------|--------|------------------------|
| **Plugin identity too weak** | `String name()` + `PluginType type()` is insufficient for marketplace, capability discovery, compatibility. | **PluginDescriptor** (id, displayName, vendor, version, capabilities, contractVersion). |
| **createRuntime() too generic** | `<T> T createRuntime(...)` introduces unsafe casting and plugin bugs can leak. | **PluginRuntime** createRuntime(...); typed clients implement PluginRuntime. |
| **Lifecycle incomplete** | Only ResourceCleanup.onExit(); plugins may need init/start/stop (e.g. warm pool, health checks). | **PluginLifecycle** (init, start, stop) documented as optional extension. |
| **Execution vs runtime blurry** | “Same plugin satisfies both” can lead to fat, ambiguous plugins. | **ExecutablePlugin** vs **RuntimePlugin** as separate contracts; plugin can expose runtimeProvider + executors. |
| **Tenant-scoped registry** | Plugins are code, not data; N tenants → N registries wastes memory. | **Global** PluginRegistry; **ConnectionRegistry** (tenant-scoped) for connections. |

---

## What Is a Plugin?

In Olo, a **plugin** is a registered capability that can be invoked in two ways:

| Usage | Description | Contract |
|-------|-------------|----------|
| **Execution tree (current)** | A node in the execution tree references a plugin by `pluginRef` (e.g. `GPT4_EXECUTOR`). The worker resolves the plugin and calls **execute(inputs, tenantConfig)** (or equivalent). Used for LLM calls, embeddings, vector stores, tools. | **ExecutablePlugin** / **ModelExecutorPlugin** etc.; see [creating-plugins-and-features](creating-plugins-and-features.md). |
| **Connection Manager (design)** | A **connection** references a plugin by `plugin` id (e.g. `openai`, `ollama`). ConnectionRuntimeManager resolves the plugin (global registry) and calls **createRuntime(ConnectionConfig)** to obtain a **cached** PluginRuntime (ModelClient, VectorClient, etc.). Pipelines use `ctx.model("openai-prod")` without knowing the plugin. | **RuntimePlugin** (descriptor, schema, createRuntime → PluginRuntime); see [connection-manager-design](connection-manager-design.md). |

**Secret providers** are a separate plugin kind (SecretProvider interface) used by the secret system; see [secret-architecture](secret-architecture.md). They are not execution or connection plugins but follow the same “register and resolve by name” pattern.

### Execution mode (workflow vs activities)

Execution **plugins** can run in different Temporal execution modes. To avoid scattering that decision across the codebase, the core contract declares a central enum and default method:

```java
public enum ExecutionMode {

    /** Runs inside the workflow thread. Must be deterministic. */
    WORKFLOW,

    /** Runs as a Temporal local activity (fast external calls). */
    LOCAL_ACTIVITY,

    /** Runs as a Temporal activity (durable, long running). */
    ACTIVITY,

    /** Runs as a child workflow (for complex sub-pipelines). */
    CHILD_WORKFLOW
}
```

Every `ExecutablePlugin` defines its execution mode via a default method on the contract:

```java
public interface ExecutablePlugin {

    default ExecutionMode executionMode() {
        // Current behavior: everything runs as an activity
        return ExecutionMode.ACTIVITY;
    }

    Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception;
}
```

- **Rule:** Every plugin **must override** `executionMode()` to declare one of `WORKFLOW`, `LOCAL_ACTIVITY`, `ACTIVITY`, `CHILD_WORKFLOW`.
- **Determinism:** When `executionMode() == WORKFLOW`, the plugin must not perform non-deterministic operations (HTTP calls, DB access, random numbers, system time, UUID generation). Allowed: pure computation such as JSON parsing, string operations, math, variable mapping, templating.
- **Router:** A central `PluginExecutionRouter` (runtime component) reads `executionMode()` and dispatches to the appropriate Temporal primitive (workflow code, local activity, activity, or child workflow). The execution tree and planner stay agnostic; they only specify `pluginRef`.

### Execution mode override (pipeline level)

While every `ExecutablePlugin` declares a default execution mode via `executionMode()`, the **execution tree** may override this behavior at the **node level**. This allows pipelines, planners, and administrators to tune execution behavior (latency, durability, debugging) without modifying plugin code.

**Node-level override**

Execution tree nodes may include an optional execution mode override:

```json
{
  "nodeId": "step-0",
  "pluginRef": "RESEARCH_TOOL",
  "executionMode": "LOCAL_ACTIVITY"
}
```

If `executionMode` is omitted on the node, the runtime uses `plugin.executionMode()` as the default.

**Resolution algorithm**

The runtime resolves execution mode using the following precedence:

1. **Node override** (execution tree configuration)  
2. **Plugin default** (`ExecutablePlugin.executionMode()`)

Conceptually:

```java
ExecutionMode resolveExecutionMode(ExecutionTreeNode node, ExecutablePlugin plugin) {
    if (node.getExecutionMode() != null) {
        return ExecutionMode.valueOf(node.getExecutionMode());
    }
    return plugin.executionMode();
}
```

**Execution router**

`PluginExecutionRouter` (or equivalent) determines the Temporal primitive used for execution:

```java
switch (mode) {
    case WORKFLOW:
        plugin.execute(inputs, tenantConfig);
        break;
    case LOCAL_ACTIVITY:
        localActivityExecutor.run(pluginRef, inputs);
        break;
    case ACTIVITY:
        activityExecutor.run(pluginRef, inputs);
        break;
    case CHILD_WORKFLOW:
        childWorkflowExecutor.run(pluginRef, inputs);
        break;
    default:
        throw new IllegalStateException("Unsupported execution mode: " + mode);
}
```

**Determinism rule remains enforced**

If a pipeline overrides a plugin to `WORKFLOW`, the runtime must still enforce determinism:

- **Forbidden in WORKFLOW mode:** HTTP calls, DB calls, random numbers, system time, UUID generation.
- **Allowed:** Pure computation, JSON parsing, string manipulation, math, variable mapping, templating.

If a plugin violates determinism when executed in `WORKFLOW` mode, execution should fail with a clear error.

**Optional: plugin override policy**

Some plugins may restrict overrides. The contract can expose an optional policy:

```java
default boolean allowExecutionModeOverride() {
    return true;
}
```

Validation (conceptual):

```java
if (node.getExecutionMode() != null
        && !plugin.allowExecutionModeOverride()) {
    throw new IllegalStateException(
        "Execution mode override not allowed for plugin " + pluginId
    );
}
```

`PluginDescriptor` can optionally expose `supportedExecutionModes` (e.g. `[ACTIVITY, LOCAL_ACTIVITY]`) so pipeline validation can reject overrides that are not supported for a given plugin.

This design doc focuses on **execution plugins** and **connection-runtime plugins**. Execution and runtime are **conceptually different** (e.g. OpenAIChatExecutor vs OpenAIClientProvider); they can share code but should be separate contracts — **ExecutablePlugin** vs **RuntimePlugin**. Using distinct names (**ExecutablePlugin**, **RuntimePlugin**, **FeaturePlugin**, **SecretProvider**) creates clear plugin categories; a single vague name like “OloPlugin” does not. Better model: a **Plugin** can expose both a **runtime provider** and **executors**; see §Execution vs runtime plugins.

---

## Runtime flow (at a glance)

When a pipeline uses a connection (e.g. `ctx.model("openai-prod")`), the resolution path is:

```
Pipeline
   ↓
ConnectionRuntimeManager
   ↓
ConnectionResolver  (resolve connection by tenantId + connectionName)
   ↓
PluginRegistry.get(pluginId)
   ↓
RuntimePlugin.createRuntime(effectiveConfig)
   ↓
PluginRuntime (e.g. ModelClient) — cached by ConnectionKey
```

This helps new developers see the full path from pipeline call to plugin runtime in one place. Plugins that need **multiple connections** (e.g. RAG: model + vector DB; SQL Agent: model + database; ETL: source DB + destination DB) resolve each dependency via **ExecutionContext** (`ctx.model(name)`, `ctx.vector(name)`, etc.). The Connection Manager resolves **one connection per call**; multi-connection orchestration is the plugin runtime’s responsibility. See [connection-manager-design §15a](connection-manager-design.md#15a-multi-connection-use-cases-and-plugin-responsibility).

### Plugin architecture diagram

How the execution engine uses the plugin system (resolution and runtime creation):

```
Execution Engine
       │
       ▼
   PluginRegistry
       │
       ▼
   RuntimePlugin
       │
       ▼
   createRuntime(config)
       │
       ▼
   PluginRuntime
       │
       ▼
Capability Interfaces
(ChatCapability
 EmbeddingCapability
 StorageCapability)
```

The engine (or Connection Runtime Manager) calls **PluginRegistry.get(pluginId)** to obtain a **RuntimePlugin**, then **createRuntime(effectiveConfig)** to get a **PluginRuntime**. The runtime implements **capability interfaces** (ChatCapability, EmbeddingCapability, etc.) so the pipeline can call `ctx.capability(ChatCapability.class, connectionName)`. See §Capability registration.

---

## Glossary

| Term | Meaning |
|------|---------|
| **Plugin** | A registered capability (execution and/or connection-runtime) identified by a **PluginDescriptor** (id, displayName, vendor, version, type, capabilities). Implemented in a module such as `olo-plugin-openai`. |
| **PluginDescriptor** | Rich metadata: **id**, **displayName**, **vendor**, **version**, **type** (PluginType), **contractVersion**, **capabilities** (e.g. chat, embeddings), optional **dependencies**. Enables marketplace, capability discovery, compatibility checks. |
| **PluginRegistry** | **Recommended: global** registry: **get(pluginId)**, **list()**, **findByCapability(capability)**. Plugins are code, not tenant data; one registry avoids N copies for N tenants. **ConnectionRegistry** (or connection store) is tenant-scoped. |
| **PluginProvider** | **Responsible for instantiating plugin implementations.** The registry may store providers rather than singletons so the worker can create per-node or per-request instances. See §PluginProvider interface. Flow: **PluginRegistry** → **PluginProvider** → **RuntimePlugin**. |
| **PluginRuntime** | Marker (or base) interface for objects returned by **createRuntime(ConnectionConfig)**. **ModelClient**, **VectorClient**, **ToolClient**, **StorageClient** extend it. Avoids raw `Object` or generic `<T>` casting. |
| **Runtime** | The object returned by **createRuntime(ConnectionConfig)**: implements **PluginRuntime** (e.g. ModelClient). Cached by ConnectionRuntimeManager and must be **thread-safe**. |
| **ConnectionSchema** | Declarative description of a plugin’s connection config **version** (schemaVersion), **fields** (name, type, required, secret). Used for UI forms, validation, documentation, and config migrations. |
| **PluginType** | Enum: MODEL, VECTOR_DB, TOOL, STORAGE, DOCUMENT_STORE, AUTH, CACHE. Used for simple typing and optional helpers. **Capability registration** (capabilities as interfaces) moves responsibility to plugins and avoids type explosion; see §Capability registration. |
| **ExecutablePlugin** | Contract for **execution tree** invocation: **execute(inputs, tenantConfig)**. Used by PLUGIN nodes. |
| **RuntimePlugin** | Contract for **Connection Manager** invocation: **createRuntime(ConnectionConfig)** → PluginRuntime. Clean separation from ExecutablePlugin. |
| **Capability (interface)** | A Java interface (e.g. ChatCapability, EmbeddingCapability) that a plugin runtime can implement. Plugins **register** capabilities; the runtime resolves by **capability class** (e.g. `ctx.capability(ChatCapability.class)`), not by a central PluginType. See §Capability registration. |

---

## Plugin Contract (Connection Manager Era)

When the Connection Manager is in use, plugins that back **connections** implement the **RuntimePlugin** contract (actual types may live in `olo-worker-plugin` or a shared API module). Use **PluginDescriptor** for identity and **PluginRuntime** for the return type to avoid generic casting and support a real ecosystem (marketplace, capability discovery, compatibility).

### Core Interface (recommended)

```java
public interface RuntimePlugin {
    PluginDescriptor descriptor();         // id, displayName, vendor, version, type, contractVersion, capabilities
    ConnectionSchema schema();             // for UI, validation, docs
    PluginRuntime createRuntime(ConnectionConfig config);
}
```

- **descriptor()** — Replaces `name()` and `type()` with rich **PluginDescriptor** (see §Plugin descriptor). Enables UI (“OpenAI (Model Provider), Vendor: Olo, Capabilities: chat, embeddings”), capability discovery, and compatibility checks.
- **schema()** — Describes config fields (name, type, optional, secret). Enables connection UI and validation; see §Connection schema.
- **createRuntime(config)** — Returns **PluginRuntime** (not `<T> T` or `Object`). **ModelClient**, **VectorClient**, **ToolClient**, **StorageClient** implement **PluginRuntime**, so ConnectionRuntimeManager can call `getModel(name)` → `(ModelClient) runtime` internally without plugin code doing unsafe casts. **Plugin bugs or wrong type cannot leak** if the contract is enforced.

### PluginDescriptor

```java
public interface PluginDescriptor {
    String id();              // e.g. "openai", "ollama" — must match connection.plugin
    String displayName();     // e.g. "OpenAI"
    String vendor();          // e.g. "olo", "openai"
    String version();         // e.g. "1.2.0"
    PluginType type();        // MODEL, VECTOR_DB, TOOL, STORAGE, ...
    String contractVersion(); // e.g. "1.0" for config compatibility
    Set<String> capabilities();  // e.g. ["chat", "embeddings", "images"]
    Set<String> dependencies();   // optional: plugin ids this plugin depends on (e.g. rag → vector, embedding)
}
```

**Why:** Unlocks **plugin marketplace**, **plugin UI**, **capability discovery**, and **compatibility checks**. Without a descriptor, plugin identity and metadata are ad-hoc and things become messy as Olo grows (no single place for UI, discovery, or version checks).

**Source of truth:** `PluginDescriptor` must remain the **runtime source of truth** for plugin metadata.

- At **compile time**, annotations in the plugin module are processed into a JSON descriptor (see [plugin-feature-annotations](plugin-feature-annotations.md)), e.g.:
  - `META-INF/olo-plugin.json` (per plugin JAR).
- At **runtime**, `PluginLoader`:
  - Reads `META-INF/olo-plugin.json`.
  - Constructs a `PluginDescriptor` instance from the JSON.
  - Registers it with `PluginRegistry`.
- Runtime and registry never reconstruct metadata from annotations or reflection; they only see `PluginDescriptor` instances built from descriptor JSON.

**Explicit link:** `META-INF/olo-plugin.json` is the serialized representation of the runtime `PluginDescriptor` model used by `olo-plugin-runtime`.

End-to-end flow:

```text
Annotations
     ↓
Annotation Processor
     ↓
META-INF/olo-plugin.json
     ↓
PluginDescriptor
     ↓
PluginRegistry

---

## Plugin Discovery (Runtime Loader)

At worker startup, plugins are discovered **solely via JSON descriptors**, not by scanning for annotations:

```text
PluginLoader
     ↓
scan plugin classpath / plugin directory
     ↓
locate META-INF/olo-plugin.json
     ↓
deserialize PluginDescriptor
     ↓
register plugin in PluginRegistry
```

**Important rule:** No reflection or annotation scanning in the runtime. Only `META-INF/olo-plugin.json` is loaded to build `PluginDescriptor` instances; annotations are compile-time only.
```

Recommended flow:

```text
Annotations
   ↓
Annotation processor → JSON descriptor (META-INF/olo-plugin.json)
   ↓
PluginLoader loads JSON
   ↓
PluginDescriptor created and registered in PluginRegistry
   ↓
Runtime uses PluginDescriptor for identity, capabilities, config, and UI metadata
```

This keeps annotations and classpath scanning out of the runtime and ensures `PluginDescriptor` is the single, explicit contract for plugin metadata.

Execution-related safety metadata (e.g. `ExecutionMode`, `retrySafe`, `deterministic`) should be represented in the descriptor and derived from annotations. For example:

```java
@OloPlugin(
    id = "search.api",
    executionMode = ExecutionMode.ACTIVITY,
    retrySafe = true,
    deterministic = false
)
```

Generated descriptor JSON:

```json
"execution": {
  "mode": "ACTIVITY",
  "retrySafe": true,
  "deterministic": false
}
```

Runtime and planners can then:

- Choose Temporal policies (activity vs workflow, retries) based on `mode` and `retrySafe`.
- Enforce determinism rules when a plugin is requested in `WORKFLOW` mode.
- Make planner safety decisions (e.g. avoid non-deterministic plugins in certain flows).

**Instantiation model (recommended):** Plugins are **singleton instances per worker** by default. `PluginRegistry` constructs one instance of each plugin per worker process and reuses it for all executions:

```text
PluginLoader
   ↓
instantiate plugin class
   ↓
register singleton in PluginRegistry
   ↓
reuse for all executions in this worker
```

This allows:

- Connection pooling and shared HTTP clients (e.g. one `OpenAIClient` per worker).
- Lower memory and startup overhead.
- Predictable lifecycle: plugin initialization at startup, cleanup on worker shutdown.

---

## Plugin Lifecycle

Plugins are often stateful (e.g. warm models, HTTP clients, caches). To avoid ad-hoc handling, define an optional lifecycle contract and drive it from bootstrap and shutdown hooks.

### Lifecycle states

Conceptual lifecycle:

- **DISCOVERED** – Descriptor (`META-INF/olo-plugin.json`) found by `PluginLoader`.
- **LOADED** – Plugin class loaded and singleton instance created.
- **INITIALIZED** – Optional `initialize()` hook run (e.g. warm models, connect to APIs, initialize caches).
- **READY** – Plugin available for execution via `PluginRegistry` / `PluginExecutor`.
- **STOPPED** – Optional `shutdown()` hook run during worker shutdown.

### Lifecycle contract

```java
public interface PluginLifecycle {

    /** Called once after the plugin is constructed and registered. */
    default void initialize() throws Exception {}

    /** Called when the worker is shutting down. */
    default void shutdown() throws Exception {}
}
```

Lifecycle is driven by bootstrap and shutdown:

- **Bootstrap**:

  ```text
  discover descriptor → load class → create instance → initialize() → READY
  ```

- **Shutdown hook**:

  ```text
  STOPPED: plugin.shutdown()
  ```

This explicit lifecycle gives plugins a clear place to perform expensive setup/teardown and keeps runtime behavior predictable.

### Runtime capability declaration (PluginCapability)

Beyond **PluginType** (MODEL, VECTOR_DB, etc.), plugins can expose finer-grained **capability metadata** so the runtime and pipelines can reason about what a plugin supports.

**Example:** A plugin declares a set of capabilities:

```java
Set<PluginCapability> capabilities();
```

**Example capability enum (or equivalent):**

| Capability   | Example use                          |
|-------------|--------------------------------------|
| **STREAMING**   | LLM streaming                        |
| **EMBEDDINGS**  | Embedding generation                 |
| **BATCH**       | Batch inference                      |
| **TRANSACTIONAL** | DB support (transactions)          |
| **ASYNC**       | Async execution                     |

**Why useful:**

- **Pipeline validation** — Pipeline requires “embeddings”; validator checks that the chosen connection’s plugin has EMBEDDINGS.
- **UI feature toggles** — Admin UI shows “Streaming” only for connections whose plugin declares STREAMING.
- **Fallback logic** — Primary connection fails; fallback selects a connection whose plugin has the same capabilities.
- **Planner reasoning** — “Need embeddings” → planner chooses a model plugin with EMBEDDINGS capability.

**Example planner logic:**

- Need embeddings → choose model plugin with **EMBEDDINGS** capability.
- Need streaming → choose connection whose plugin has **STREAMING**.

This can coexist with **PluginDescriptor.capabilities()** (e.g. string set `["chat", "embeddings", "images"]`): use string capabilities for discovery and manifests, and **PluginCapability** (enum or typed constants) for programmatic checks and planner/fallback logic. A well-known mapping keeps the two aligned.

**Version semantics** — Three version fields appear in the design; their roles are distinct:

| Field | Meaning |
|-------|---------|
| **version** | Plugin **implementation** version (e.g. `"1.2.0"`). Identifies the plugin JAR/release for humans and marketplace. |
| **contractVersion** | Compatibility with the **Olo runtime** and plugin contract. Used to reject or migrate when the worker expects a minimum contract (e.g. `"1.0"`). |
| **schemaVersion** | **Connection configuration shape** (on ConnectionSchema). When config fields change, bump this; enables validation and migrations (e.g. v1 → v2 add default `organization`). |

Keeping these explicit avoids confusion between “plugin release version,” “contract compatibility,” and “config schema.”

### PluginRuntime (avoid generic createRuntime)

**Problem with `<T> T createRuntime(...)`:** Introduces unsafe casting risk internally; plugin bugs can leak. **Better:** a single return type that all typed clients implement.

```java
public interface PluginRuntime extends AutoCloseable {
    @Override
    void close();

    // Optional: health() for connection test and monitoring
    default HealthStatus health() { return HealthStatus.unknown(); }
}
```

Cache eviction in ConnectionRuntimeManager **must** call `runtime.close()` when a runtime is evicted (e.g. via Caffeine `removalListener((key, runtime, cause) -> runtime.close())`). Otherwise long-running workers leak resources. See [connection-manager-design](connection-manager-design.md) §6d.

**ModelClient**, **VectorClient**, **ToolClient**, **StorageClient** extend **PluginRuntime**. **ConnectionRuntimeManager ensures runtime type matches plugin descriptor type** (e.g. **MODEL** → **ModelClient**, **VECTOR_DB** → **VectorClient**). The manager casts once internally based on that fixed mapping, not on arbitrary plugin-supplied generics.

**Alternative (also valid):** Typed factory methods on the plugin:

- `ModelClient createModelRuntime(ConnectionConfig config)`
- `VectorClient createVectorRuntime(ConnectionConfig config)`  
- etc.

Then no runtime cast; the plugin declares which it implements. Either approach avoids raw `Object` and generic `<T>` from plugin code.

### Optional Extensions

| Method | Purpose |
|--------|---------|
| **testConnection(ConnectionConfig config)** | Optional. Used by “Test connection” in UI; returns success/failure without caching. |
| **requiredSecrets()** | Optional. Returns `Set<String>` of logical secret ids (e.g. `["openai.api_key"]`). Enables validation at pipeline/connection deploy time: “Missing secret openai.api_key”. See [secret-architecture](secret-architecture.md). |
| **runtime.health()** | Optional on **PluginRuntime**. Used by connection test and runtime monitoring; improves observability. |
| **plugin.health()** / **PluginHealth check()** | Optional on **RuntimePlugin**. **Plugin-level health** is useful because a **runtime may not yet exist** (no connection created). Examples: OpenAI plugin → check DNS / base URL reachable; Vector plugin → check schema or service reachable. Platform health dashboards can aggregate plugin health without creating a runtime. |

### Thread Safety Requirement

**Plugin runtime instances must be thread-safe.** Cached runtimes are reused across:

- Multiple workflow activities
- Concurrent pipeline executions

Plugins must ensure that the client returned by **createRuntime(...)** is safe for concurrent use or internally synchronized. Document this in the plugin contract; without it, cached reuse will lead to race conditions.

---

## Connection Schema

Each plugin declares a **ConnectionSchema** describing its connection config. This supports:

- **UI** — Render connection forms per plugin (fields, types, placeholders for secrets).
- **Validation** — Validate config on connection create/update (required fields, types, secret refs).
- **Documentation** — Generate docs or tooltips from schema.

**Example (conceptual):**

```java
// OpenAIPlugin.schema()
fields:
  baseUrl   (string, optional)
  apiKey    (secret)           // resolved via ${secret:...} or secret_ref
  timeout   (number, optional)
  model     (string, optional)
```

Schema can be a list of field descriptors (name, type, required, secret, default). The Connection Manager (or admin UI) uses it when creating/editing a connection that uses this plugin.

### Plugin configuration versioning

Plugin configuration schema may change over time (new required field, renamed field, etc.). Without versioning, config compatibility becomes messy.

**Add a schemaVersion (or version) to ConnectionSchema:**

```java
ConnectionSchema {
  version   // e.g. "1", "2" — identifies the shape of fields
  fields   // list of field descriptors
}
```

**Example:** openai v1 (fields: `apiKey`); openai v2 (fields: `apiKey`, `organization`). When the plugin adds a new field, bump **schemaVersion**. The Connection Manager (or admin UI) can then:

- **Validate** — Reject or migrate config created for an older schema version.
- **Migrate** — Offer or run migrations (e.g. v1 → v2: add default `organization` or prompt the user).

Otherwise config compatibility and upgrades are ambiguous. **contractVersion** (on PluginDescriptor) applies to the plugin contract; **schemaVersion** (on ConnectionSchema) applies to the connection config shape and enables migrations.

---

## Plugin Types and Typed Runtimes

**PluginType** (and connection type) should be one of:

- **MODEL** — LLM / chat / completion (e.g. OpenAI, Ollama). Runtime: **ModelClient**.
- **VECTOR_DB** — Embeddings and vector store. Runtime: **VectorClient**.
- **TOOL** — Callable tools (APIs, functions). Runtime: **ToolClient**.
- **STORAGE** — Blob or object storage. Runtime: **StorageClient**.
- **DOCUMENT_STORE** — Document indexing/retrieval. Runtime: TBD or DocumentStoreClient.
- **AUTH**, **CACHE** — Reserved for future use.

ConnectionRuntimeManager can expose **type-based helpers** for convenience:

- `ModelClient getModel(String connectionName)`
- `VectorClient getVector(String connectionName)`
- `ToolClient getTool(String connectionName)`
- `StorageClient getStorage(String connectionName)`

These rely on a **central mapping** (PluginType → client type). For a scalable **plugin ecosystem**, prefer **capability registration** (see §Capability registration): plugins advertise the **capability interfaces** they implement, and the runtime resolves by capability class. Type-based helpers can remain as thin wrappers over `capability(ModelClient.class, connectionName)` if desired.

Internally: resolve connection → plugin → **createRuntime(effectiveConfig)** → **PluginRuntime**. With **capability registration** (see §Capability registration), the manager asks “does this runtime implement ChatCapability?” and returns it as that interface; no central enum of plugin types is required. Otherwise the manager uses a fixed mapping (MODEL → ModelClient, etc.). **Capabilities** (from descriptor) allow finer-grained validation (e.g. pipeline requires “chat” + “embedding”; plugin declares `capabilities: [chat, embeddings, images]`).

---

## Capability registration (capabilities as interfaces)

**Idea:** Instead of the runtime owning a fixed list of **PluginTypes** (MODEL, VECTOR_DB, TOOL, …) and a central mapping (MODEL → ModelClient, etc.), **plugins register the capability interfaces they implement**. The runtime does not need to know plugin types—it only asks: “who implements this capability?” and resolves dynamically. Responsibility moves from the **core runtime** to **plugins**.

### Capability interfaces

Capabilities are **Java interfaces** defined in the API (e.g. `olo-worker-plugin`):

```java
public interface ChatCapability {
    ChatResponse chat(ChatRequest request);
}

public interface EmbeddingCapability {
    float[] embed(String text);
}

public interface ImageCapability { ... }
public interface AudioCapability { ... }
```

Plugin **runtimes** (the object returned by `createRuntime(...)`) implement one or more of these interfaces:

```java
public class OpenAIClient implements
        PluginRuntime,
        ChatCapability,
        EmbeddingCapability,
        ImageCapability
{
    @Override public ChatResponse chat(ChatRequest request) { ... }
    @Override public float[] embed(String text) { ... }
    // ...
}
```

The runtime does **not** maintain a list of plugin types. It only needs to know **capability interfaces** and to ask a plugin runtime: “does this instance implement `ChatCapability`?”

### Runtime API (capability-driven)

ConnectionRuntimeManager (or pipeline context) exposes a **capability-based** API:

```java
<T> T capability(Class<T> capabilityInterface, String connectionName);
<T> T capability(Class<T> capabilityInterface);  // optional: auto-select a connection that supports this capability
```

**Examples:**

- `ChatCapability chat = ctx.capability(ChatCapability.class, "openai-prod"); chat.chat(request);`
- `EmbeddingCapability emb = ctx.capability(EmbeddingCapability.class);` — runtime finds a connection that implements `EmbeddingCapability` (e.g. from pipeline config or default).

Pipelines become **capability-driven**: “I need something that can do chat” rather than “I need the model named openai-prod.” That enables **automatic provider selection** later (e.g. route to first available or best-fit plugin by capability).

### What this enables

| Benefit | Description |
|--------|-------------|
| **No central type mapping** | Current: MODEL → ModelClient, VECTOR_DB → VectorClient (runtime owns the mapping). Capability model: plugin **advertises** interfaces it supports; runtime resolves dynamically. Much more scalable. |
| **Multiple capabilities per plugin** | OpenAI can implement ChatCapability, EmbeddingCapability, ImageCapability, AudioCapability on **one** runtime. No need to squeeze everything into a single PluginType + string list; it’s **type-safe and discoverable**. |
| **Pipelines capability-driven** | `ctx.capability(ChatCapability.class)` or `ctx.capability(ChatCapability.class, "openai-prod")`. Enables “find best plugin for this capability” and future auto-routing. |
| **Avoids plugin type explosion** | Without capability registration, the runtime may grow: MODEL, VECTOR_DB, EMBEDDING_PROVIDER, IMAGE_MODEL, AUDIO_MODEL, TOOL_PROVIDER, DOCUMENT_STORE, CACHE, AUTH, RAG_ENGINE, … Capability registration avoids this: new capabilities are **new interfaces**, not new enum values in the core. |

### Relation to descriptor.capabilities()

The descriptor already has **capabilities: ["chat", "embedding", "images"]**. That remains useful for **discovery and manifest** (e.g. PluginRegistry.findByCapability("embedding"), plugin manifest in JAR). The improvement is: **capabilities also become interfaces**. So:

- **String capabilities** — For manifests, UI, and registry lookup (findByCapability("embedding")).
- **Capability interfaces** — For type-safe resolution at runtime: `ctx.capability(EmbeddingCapability.class)`.

A well-known mapping (e.g. `"chat"` → `ChatCapability.class`) keeps the two in sync. Plugins that implement `ChatCapability` should declare `"chat"` in descriptor.capabilities() so discovery and runtime resolution stay aligned.

### Precedent: Kubernetes and Temporal

- **Kubernetes** — Storage plugins register **CSI**, network plugins register **CNI**. The scheduler does not know implementations; it asks “who supports capability X?”
- **Temporal** — Workers **register** activities and workflows; the runtime routes calls without knowing concrete implementations.

Same principle: **plugins register capabilities; the core resolves by capability, not by a fixed type list.**

### Why this matters as Olo grows

With many plugins (e.g. 20 model plugins, 10 vector plugins, 15 tool plugins), capability registration allows:

- **Plugin discovery** — “Which runtimes implement EmbeddingCapability?”
- **Auto routing** — “Give me any connection that supports chat.”
- **Capability composition** — Pipelines depend on capabilities, not on provider names.

Without rewriting the core runtime or adding new PluginType enum values for every new kind of provider.

---

## PluginRegistry

**Recommended: global registry, tenant-scoped connections.**

- **Plugins are code, not data.** The same OpenAI plugin implementation is used for every tenant; only connection **config** (and secrets) are tenant-specific. A **tenant-scoped** PluginRegistry implies one registry copy per tenant (e.g. 100 tenants → 100 registries), which wastes memory and complicates registration.
- **Better design:**
  - **PluginRegistry** — **Global**: `get(pluginId)` returns the plugin (or provider). Single registration at startup; no tenant dimension.
  - **Connection store / ConnectionRegistry** — **Tenant-scoped**: connections (and their plugin id, config, version) are per tenant. ConnectionRuntimeManager resolves **connection** by `(tenantId, connectionName)` and then resolves the **plugin** by `pluginId` from the **global** PluginRegistry.

**API (recommended):**

- **get(pluginId)** — Resolve a plugin (or provider) by id. Used by ConnectionRuntimeManager and execution engine.
- **list()** — Return all registered plugins (or their descriptors). Enables marketplace UI, admin lists, and discovery.
- **findByCapability(capability)** — Return plugins that declare a given capability (e.g. `findByCapability("embedding")`). Extremely useful for pipeline validation (“which plugins can do embeddings?”) and UI (“show plugins with capability X”).

**Registration:** At worker startup, all plugin providers (internal and, if enabled, community) are registered **once**. **Usage:** Execution engine and ConnectionRuntimeManager call **PluginRegistry.get(pluginId)** (no tenantId). Tenant context is applied at **connection** resolution and at **secret** resolution, not at plugin lookup.

**Contract version** — Stored in **PluginDescriptor.contractVersion()**; registry or descriptor is used for config compatibility checks. See [versioned-config-strategy](versioned-config-strategy.md).

The registry does **not** know about connections or vaults.

### PluginProvider interface

The registry may store **providers** rather than singleton plugin instances so the worker can create per-node or per-request instances. Defining the interface removes ambiguity:

```java
public interface PluginProvider {
    RuntimePlugin create();
}
```

Alternatively, a generic form `PluginProvider<T extends RuntimePlugin>` with `T create()` is possible if the registry needs to expose typed creation. The non-generic form is sufficient when the registry returns **RuntimePlugin** and callers use **descriptor().type()** for the fixed mapping (MODEL → ModelClient, etc.). Connection resolution (tenant-scoped) and secret resolution are handled by ConnectionResolver and SecretResolver.

---

## Lifecycle

### Plugin loading lifecycle

Loading order at worker startup (a small diagram helps):

```
worker startup
   ↓
load internal plugins
   ↓
load community plugins (e.g. OLO_PLUGINS_DIR)
   ↓
validate dependencies (descriptor.dependencies())
   ↓
register providers (PluginRegistry)
```

After this, **get(pluginId)**, **list()**, and **findByCapability(...)** are available. Connection resolution and runtime creation happen later, on first use. For **how** plugins are discovered and loaded (JAR structure, manifest, dependency validation, classloader isolation, security policy), see [plugin-discovery-and-loading](plugin-discovery-and-loading.md).

### Registration

1. **Internal plugins** — Built into the worker; registered explicitly (e.g. `InternalPlugins.createPluginManager()` in `olo-internal-plugins`). No ServiceLoader for internals.
2. **Community plugins** — JARs in a configured directory (e.g. `OLO_PLUGINS_DIR`); loaded with a restricted classloader and registered per tenant.
3. **Secret providers** — Registered with the secret system (separate from execution/connection plugins); see [secret-architecture](secret-architecture.md).

### Execution (today)

- **Per-node instance** — For execution tree nodes, the worker typically creates or obtains one plugin instance per node (per run). Same node in a loop may reuse the same instance. See [creating-plugins-and-features](creating-plugins-and-features.md) §1.1.1.
- **No caching across connections** — Today, execution is keyed by `pluginRef` and node; connection-based caching is introduced with the Connection Manager.

### Connection Manager (design)

- **Cached runtime** — ConnectionRuntimeManager caches by **ConnectionKey** (tenantId, connectionName, version). First use: resolve connection → createRuntime → cache. Subsequent uses: return cached runtime until invalidated (connection update or secret rotation).
- **Lazy** — Runtimes are created only when first requested (e.g. first `ctx.model("openai-prod")`). No eager loading of all connections.

### Plugin lifecycle (beyond shutdown)

**ResourceCleanup.onExit()** covers shutdown only. As Olo grows, plugins may need a fuller lifecycle:

- **init()** — One-time setup (e.g. load config, validate environment).
- **start()** — Start services (e.g. warm connection pool, load schema for a vector DB plugin).
- **stop()** — Graceful teardown before process exit (e.g. drain pool, cancel background tasks).

**Optional interface:** **PluginLifecycle** (init, start, stop). **ResourceCleanup.onExit()** can call **stop()** or remain the single shutdown hook. Future plugins (e.g. background health checks, warm caches) will benefit from explicit start/stop. Not required for v1 but document the direction.

### Shutdown

- **Resource cleanup** — At worker shutdown, **ResourceCleanup.onExit()** is invoked for all plugins (and features). Plugins can close HTTP clients, release connections, etc. See [creating-plugins-and-features](creating-plugins-and-features.md) and [architecture-and-features](architecture-and-features.md).

---

## Execution vs runtime plugins

Execution plugins (tree) and runtime plugins (Connection Manager) are **conceptually different**. Mental model: **execution = action** (run something once); **runtime = client provider** (supply a reusable, cached client). This is how large orchestration systems separate “do a task” from “give me a handle to a service.”

| Aspect | Execution (tree) | Runtime (Connection Manager) |
|--------|-------------------|------------------------------|
| **Contract** | **ExecutablePlugin**: execute(inputs, tenantConfig) | **RuntimePlugin**: createRuntime(ConnectionConfig) → PluginRuntime |
| **Example** | OpenAIChatExecutor (run one chat call) | OpenAIClientProvider (create cached HTTP client) |
| **Invocation** | Per node, per run (or per-node instance) | Per connection, cached by ConnectionKey |

**Same plugin can satisfy both** (e.g. one module implements both contracts), but the **interfaces** should be separate so plugins do not become fat and ambiguous. **Better model:**

- **Plugin** (conceptually) can expose:
  - **runtimeProvider** — Implements RuntimePlugin (createRuntime). Used by Connection Manager.
  - **executors** — One or more ExecutablePlugin implementations (e.g. MODEL_EXECUTOR, EMBEDDING). Used by execution tree nodes.

They can share code (e.g. same HTTP client builder) but **ExecutablePlugin** and **RuntimePlugin** remain distinct contracts. This keeps the architecture clean and avoids one mega-interface.

---

## Example: OpenAI plugin

A concrete example ties the concepts together. The **OpenAI plugin** (e.g. in `olo-plugin-openai`) looks like this:

| Piece | What it provides |
|-------|-------------------|
| **Descriptor** | `id: openai`, `displayName: "OpenAI"`, `vendor: openai`, `type: MODEL`, `capabilities: [chat, embeddings, images]`. |
| **Runtime** | **RuntimePlugin.createRuntime(config)** → **OpenAIClient** (implements **ModelClient** / **PluginRuntime**). Cached by ConnectionRuntimeManager when pipelines call `ctx.model("openai-prod")`. |
| **Executors** | **OpenAIChatExecutor**, **OpenAIEmbeddingExecutor** (implement **ExecutablePlugin**). Used by execution tree nodes that reference this plugin by `pluginRef`. |

One module can expose both: the **runtime** for connection-based, cached usage (Connection Manager), and the **executors** for direct execution-tree invocation. Shared code (e.g. HTTP client, auth) lives behind both.

---

## Capability system

**PluginType** alone is not enough. Example: OpenAI supports **chat**, **embeddings**, **images**, **audio**. A pipeline may require “chat + embedding”; validation should resolve plugins that declare those **capabilities**.

- **PluginDescriptor.capabilities()** — e.g. `Set<String>`: `["chat", "embedding", "images"]`. Pipeline validation can check that every required capability is satisfied by the chosen plugin(s). Enables powerful pipeline validation and discovery (e.g. “show all plugins with capability embedding”). For **type-safe resolution at runtime**, capabilities are also **interfaces** (ChatCapability, EmbeddingCapability, etc.); see §Capability registration.

**Capability types (well-known constants):** Raw strings are flexible but error-prone (`embedding` vs `embeddings` vs `vector-embedding`). Better long term: **well-known constants** (enum or shared constants), e.g. **CHAT**, **EMBEDDING**, **IMAGE**, **AUDIO**. Plugins can still declare custom capability strings for extensibility, but using constants for common capabilities avoids typos and makes **findByCapability** and pipeline validation consistent. Example: `Capability.CHAT`, `Capability.EMBEDDING`; registry accepts both known and custom strings.

---

## Plugin sandbox (community plugins)

Community (and third-party) plugins can be dangerous. **Isolation strategies** reduce risk and are worth highlighting for security-sensitive deployments:

| Strategy | Description |
|----------|-------------|
| **Classloader isolation** | Separate classloader per plugin (or per JAR) so plugin code cannot see or mutate core classes and dependency conflicts are contained. |
| **Permission sandbox** | Restrict filesystem (only allowed paths), network (allowlist or none), reflection (limit to public API). Configurable allowlists, deny by default. |
| **Resource quotas** | Limit memory and CPU time per plugin invocation or per runtime to prevent runaway plugins. |

Beyond a **restricted classloader** (denied packages), consider:

- **Filesystem** — Only allowed paths (e.g. temp dir, or none).
- **Network** — Allowlist of hosts or “no network” for untrusted plugins.
- **Reflection** — Limit to public API types.

**Community plugins** especially. Long term: a **plugin security policy** (e.g. configurable allowlists, deny by default). See also [connection-manager-design §24](connection-manager-design.md#24-plugin-isolation-security).

---

## Plugin dependencies

Some plugins may depend on other plugins (e.g. **rag-plugin** depends on **vector-plugin** and **embedding-plugin**). **PluginDescriptor.dependencies()** — optional `Set<String>` of plugin ids — enables:

- **Validation at load** — Ensure dependencies are registered before the plugin is used.
- **Ordering** — Start/stop or init order when lifecycle is introduced.
- **Discovery** — “Plugins that depend on X” in a marketplace or admin UI.

---

## Optional future capabilities

- **Plugin marketplace** — The design already supports it: list plugins by **PluginDescriptor** (displayName, vendor, capabilities); users install plugin JARs (e.g. into OLO_PLUGINS_DIR).
- **Hot plugin reload** — Because plugins are loaded from a directory (OLO_PLUGINS_DIR), later you can support “drop JAR → plugin available” without restart. Very powerful for development and ops.
- **Plugin isolation** — Long term: one **classloader per plugin** (or per JAR) to prevent dependency conflicts between community plugins.

---

## Integration Points

| System | How plugins are used |
|--------|----------------------|
| **Execution tree** | Node has `pluginRef` (e.g. `GPT4_EXECUTOR`). Engine resolves plugin via PluginRegistry and calls **execute(inputs, tenantConfig)**. |
| **Connection Manager** | Connection has `plugin` (e.g. `openai`). ConnectionRuntimeManager resolves **connection** (tenant-scoped) then **plugin** via **global** PluginRegistry.get(pluginId) and calls **createRuntime(effectiveConfig)**; result is cached. Pipeline uses `ctx.model("openai-prod")` or **capability-driven** `ctx.capability(ChatCapability.class, "openai-prod")` / `ctx.capability(ChatCapability.class)` (auto-select). |
| **Secrets** | Secret **providers** (Vault, AWS, env) are plugins implementing SecretProvider; they are not resolved from the same registry as execution/connection plugins but follow a similar “register by name” pattern. |

---

## Module Layout

```
olo-worker-plugin          # Contracts: ExecutablePlugin, RuntimePlugin, PluginRegistry, PluginProvider
olo-annotations            # @RuntimePlugin (id, displayName, contractType, contractVersion, ...)
olo-internal-plugins       # Internal plugin registration (e.g. Ollama, internal tools)
olo-plugin-openai          # OpenAI execution + (when CM exists) connection runtime
olo-plugin-ollama          # Ollama execution + connection runtime
olo-plugin-*               # Other execution/connection plugins
olo-plugin-secret-vault    # Secret provider (see secret-architecture)
olo-plugin-secret-env      # Secret provider
```

Execution plugins and connection runtimes can live in the same module but should implement **separate contracts**: **ExecutablePlugin** (for the tree) and **RuntimePlugin** (for the Connection Manager). Share code (e.g. client builder), not one fat interface.

---

## Versioning and Compatibility

- **Contract version** — Plugins declare a **contractVersion** (e.g. `@RuntimePlugin(contractVersion = "1.0")`). Used for config compatibility: pipeline config can require a minimum plugin contract version. See [versioned-config-strategy](versioned-config-strategy.md).
- **Connection schema** — When the schema changes (new required field, removed field), use **schemaVersion** (see §Plugin configuration versioning) and consider bumping contract version and documenting migration.

---

## Observability

When the Connection Manager is used, observability events (see [connection-manager-design](connection-manager-design.md) §13) include:

- **ConnectionResolvedEvent** — tenantId, connectionName, **plugin**, durationMs
- **RuntimeCreatedEvent** — tenantId, connectionName, **plugin**, runtimeType

So all plugins are observed under the same event shape; the **plugin** field identifies which implementation was used.

---

## Plugin failure behavior

Operators need a clear picture of what happens when things go wrong. Typical behavior:

| Scenario | Recommended behavior |
|----------|------------------------|
| **Plugin runtime creation fails** (e.g. invalid config, secret missing, network error) | **Fail the pipeline execution** (or the connection resolution step). Log the plugin error with plugin id, connection name, and cause. Do not cache a failed result; retry may succeed after config/secret fix. |
| **Plugin dependency missing** (e.g. a plugin declares `dependencies: [vector-plugin]` but it is not registered) | **Fail at load time** (or at first use): refuse to register the plugin or fail **get(pluginId)** with a clear error. Log which dependency is missing. Prevents silent misconfiguration. |
| **Plugin throws during createRuntime() or execute()** | **Fail the current operation** (pipeline run or connection test). Log with plugin id, stack trace (or sanitized message), and context. Optionally emit a **PluginErrorEvent** for observability. |

In all cases: **log the plugin error** and **fail the affected operation** (pipeline execution or connection resolution). Avoid swallowing exceptions; operators need visibility to fix config, secrets, or dependencies.

---

## Summary

| Aspect | Design |
|--------|--------|
| **Identity** | **PluginDescriptor**: id, displayName, vendor, version, type, contractVersion, capabilities, optional dependencies. Resolved via **global** PluginRegistry.get(pluginId). Connections remain tenant-scoped. |
| **Contract** | **RuntimePlugin**: descriptor(), schema(), **PluginRuntime** createRuntime(config). Separate **ExecutablePlugin** for execution tree. Optional: testConnection(), requiredSecrets(); runtime.health(); plugin.health() for plugin-level checks. |
| **Return type** | **PluginRuntime** (ModelClient, VectorClient, ToolClient, StorageClient implement it). No raw Object or generic `<T>` from plugin. |
| **Thread safety** | Cached runtimes must be thread-safe. |
| **Schema** | ConnectionSchema (version/schemaVersion, fields) for UI, validation, docs, migrations. Version semantics: **version** (implementation), **contractVersion** (Olo contract), **schemaVersion** (config shape). |
| **Lifecycle** | Load order: internal → community → validate dependencies → register. Optional **PluginLifecycle** (init, start, stop). ResourceCleanup.onExit() for shutdown. |
| **Registry** | **Global** PluginRegistry: get(pluginId), list(), findByCapability(capability). **ConnectionRegistry** (or connection store) tenant-scoped. **PluginProvider**: RuntimePlugin create(). |
| **Capabilities** | descriptor.capabilities() (strings) for discovery/manifest; **capability interfaces** (ChatCapability, EmbeddingCapability) for type-safe resolution. ctx.capability(CapabilityClass) or ctx.capability(CapabilityClass, connectionName). See §Capability registration. |
| **Failure behavior** | Runtime creation / dependency missing / plugin throw → fail operation, log plugin error; no silent swallow. See §Plugin failure behavior. |
| **Future** | Marketplace, hot reload, plugin isolation (per-classloader), sandbox (filesystem/network/reflection policy), descriptor.dependencies(). |

---

## Related architecture docs

All architecture documents in **docs/arcitecture**:

| Document | Description |
|----------|-------------|
| [architecture-and-features](architecture-and-features.md) | High-level worker architecture, module map, features, bootstrap, execution flow. |
| [connection-manager-design](connection-manager-design.md) | Connection abstraction, ConnectionRuntimeManager, ConnectionResolver, SecretResolver, caching, ResolvedConnection, ConnectionKey. |
| [event-communication-architecture](event-communication-architecture.md) | Execution events (runId, chat UI), connection/plugin observability events, lifecycle events (cache invalidation), transport, masking. |
| [execution-tree-design](execution-tree-design.md) | Execution tree as declarative pipeline, node types, variable model, ExecutionEngine, NodeExecutor, scope. |
| [feature-design](feature-design.md) | Feature phases (PRE, POST_SUCCESS, POST_ERROR, FINALLY), FeatureRegistry, FeatureAttachmentResolver, privilege (internal vs community). |
| [plugin-design](plugin-design.md) | **This document.** Plugin contract, PluginDescriptor, PluginRuntime, PluginRegistry, execution vs runtime. |
| [plugin-discovery-and-loading](plugin-discovery-and-loading.md) | How plugins enter the system: JAR structure, plugin manifest, dependency validation, classloader isolation, security policy. |
| [secret-architecture](secret-architecture.md) | Secret resolution, Secret Registry, ConfigInterpolator, SecretResolver, vault-agnostic references, structured secrets, masking. |

For step-by-step plugin creation and security rules, see [creating-plugins-and-features](../creating-plugins-and-features.md). For how plugins are used by connections and caching, see [connection-manager-design](connection-manager-design.md).
