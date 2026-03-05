# Connection Manager — Design Specification

A flexible **Connection Manager** is the runtime bridge between pipelines and plugins. When designed well, it enables **multi-tenancy**, **hot reload**, **plugin independence**, and **runtime overrides**.

---

## Glossary

| Term | Meaning |
|------|---------|
| **Connection** | A configured instance of a plugin (e.g. `openai-prod`): stored config, plugin id, type, optional secret ref. Resolved per tenant. |
| **Plugin** | An implementation that knows how to create a **runtime** for a given connection type (e.g. OpenAI, Ollama, pgvector). Registered in PluginRegistry; never referenced by vault or connection details in the manager. |
| **Runtime** | The actual client/instance used at execution time (e.g. an OpenAI client, a vector DB client). Created by the plugin from **ResolvedConnection** / effective config; cached by ConnectionRuntimeManager. |
| **Resolver** | Component that turns a name + context into something concrete: **ConnectionResolver** → connection + overrides → **ResolvedConnection**; ConfigInterpolator/SecretResolver → resolved config. Stateless where possible; caching lives in ConnectionRuntimeManager. |
| **ConnectionKey** | Value object `(tenantId, connectionName, version)` used as cache key for runtimes. Avoids string concatenation and mistakes; see §6a. |

---

## Common mistake vs correct design

**This is the most common mistake in connection managers.**

❌ **Wrong design:** Connections tied too tightly to plugins.

- Example: **ConnectionManager** knows plugin details.
- This breaks extensibility.

✔ **Correct design (what this spec follows):**

The connection manager should **only** do:

1. Resolve connection  
2. Resolve secrets  
3. Merge overrides  
4. Load plugin  
5. Create runtime  

**The plugin does everything else.** This keeps the system **plugin-driven**, not connection-manager-driven. This design mostly follows that already.

---

## One missing component: ConnectionResolver layer

Right now resolution logic could sit entirely in the connection manager. **Better separation:** introduce a **ConnectionResolver** layer.

```
ConnectionRuntimeManager
     ↓
ConnectionResolver
     ↓
ConfigInterpolator
     ↓
SecretResolver
     ↓
PluginRegistry
```

**Why?**

- Unit testing resolution in isolation  
- Future config sources (file, env, other stores) without touching the runtime manager  
- Easier caching logic (cache lives in ConnectionRuntimeManager; resolution is stateless)

### Connection and secret resolution flow (full path)

End-to-end path from pipeline request to cached runtime:

```
Pipeline
   │
   ▼
ConnectionRuntimeManager
   │
   ▼
ConnectionResolver
   │
   ▼
ConfigInterpolator
   │
   ▼
SecretResolver
   │
   ▼
SecretRegistry
   │
   ▼
SecretProvider
   │
   ▼
Vault / AWS / Env
```

Then, with **resolved config** (no `${secret:...}` left):

```
Resolved Config
   │
   ▼
createRuntime(effectiveConfig)
   │
   ▼
Cached PluginRuntime
```

This shows the **runtime resource pipeline**: connection name → resolved connection + interpolated config (including resolved secrets) → plugin.createRuntime() → cached runtime. See [secret-architecture](secret-architecture.md) for SecretResolver, Secret Registry, and providers.

---

## ResolvedConnection (Explicit Resolution Output)

Resolution should produce an **explicit object**, not an implicit config blob.

**ResolvedConnection:**

| Field | Meaning |
|-------|---------|
| `connectionId` | From DB/store |
| `tenantId` | Tenant context |
| `plugin` | Plugin identifier (for PluginRegistry lookup) |
| `type` | MODEL, VECTOR_DB, etc. |
| `effectiveConfig` | Merged connection config + pipeline overrides + resolved secrets (no `${secret:...}` left) |

**Flow:**

```
ConnectionResolver
      ↓
ResolvedConnection
      ↓
ConnectionRuntimeManager (cache: Map<ConnectionKey, Runtime> — see §6)
      ↓
Plugin.createRuntime(resolvedConnection.getEffectiveConfig())
```

**Benefits:** better logging (log connectionId/tenantId), easier debugging (inspect ResolvedConnection before runtime creation), clearer lifecycle (resolution vs runtime creation are distinct).

---

## Naming: ConnectionRuntimeManager

The pipeline API stays:

- `ctx.model("openai-prod")` — excellent and unchanged.

**Internally** name the manager **ConnectionRuntimeManager**, because it manages **runtime instances**, not just connection metadata. The name makes the responsibility (caching + runtime lifecycle) explicit.

---

## Final Architecture (Ideal)

```
Pipeline
   ↓
ExecutionContext
   ↓
ConnectionRuntimeManager
   ↓
ConnectionResolver
   ↓
ConfigInterpolator
   ↓
SecretResolver
   ↓
PluginRegistry
   ↓
PluginRuntime
```

**Responsibilities:**

| Component | Responsibility |
|-----------|-----------------|
| ExecutionContext | pipeline runtime |
| ConnectionRuntimeManager | caching + runtime |
| ConnectionResolver | resolve config |
| ConfigInterpolator | interpolate ${secret:...}, ${env:...}, etc. (recursive) |
| SecretResolver | resolve secrets (registry → provider → vault) |
| PluginRegistry | plugin discovery |
| PluginRuntime | actual client |

Very clean separation.

---

## 1. Connection Abstraction (Core Concept)

**What:** A connection represents a configured instance of a plugin.

**Examples:** `openai-prod`, `ollama-local`, `pgvector-main`

**How:**

- **Database table:** `connections`
- **Fields:**
  - `id`
  - `tenant_id`
  - `name`
  - `plugin` (plugin identifier, e.g. `openai`, `ollama`)
  - `type` (e.g. `MODEL`, `VECTOR_DB`)
  - `config_json`
  - `secret_ref`
  - `enabled`
  - **`version`** — incremented on every update. Used in cache key so distributed workers do not use stale runtime instances (see §6).
  - **`state`** (optional) — e.g. `ACTIVE`, `DISABLED`, `DEPRECATED`. Use cases: migration between models, soft disable, scheduled retirement. When resolving a connection in state `DEPRECATED`, emit a warning (e.g. `ctx.model("openai-prod")` → log or event: "Connection openai-prod is deprecated"). When `DISABLED`, resolution can fail or return an error so pipelines do not use the connection.
  - **`deprecated_since`** (optional) — Timestamp or version when the connection was marked deprecated. Enables UI to show “Deprecated since …”.
  - **`replacement_connection`** (optional) — Name (or id) of the recommended replacement connection. UI can warn users and suggest migration (e.g. “Use anthropic-prod instead”).

**Example config_json:**

```json
{
  "baseUrl": "https://api.openai.com",
  "timeout": 30
}
```

---

## 2. Plugin Registry Integration

**What:** ConnectionRuntimeManager must never know plugin implementation details (resolution via ConnectionResolver → PluginRegistry). Plugins are resolved through a registry.

**How:**

- **PluginRegistry** (or existing registry) holds plugin implementations.
- Example registration:
  - `pluginRegistry.register(new OpenAIPlugin());`
  - `pluginRegistry.register(new OllamaPlugin());`
- ConnectionResolver loads connection; plugin is resolved by connection’s `plugin` field:
  - `pluginRegistry.get(connection.plugin)`
- Keeps the system fully extensible; new plugins are added without changing ConnectionRuntimeManager or ConnectionResolver.

---

## 3. Runtime Instance Creation

**What:** ConnectionRuntimeManager converts **Connection → ResolvedConnection → Plugin Runtime Instance** (using ConnectionResolver, ConfigInterpolator/SecretResolver, PluginRegistry).

**How:**

- **Plugin interface** (recommended — avoid returning `Object`; use typed runtime interfaces and schema):

  ```java
  interface OloPlugin {
      String name();
      PluginType type();                    // MODEL, VECTOR_DB, TOOL, STORAGE, ...
      ConnectionSchema schema();            // for UI, validation, docs (see below)
      <T> T createRuntime(ConnectionConfig config);  // actual return: ModelClient | VectorClient | ToolClient | StorageClient | ...
  }
  ```

  **Typed return:** Plugins should return **ModelClient**, **VectorClient**, **ToolClient**, **StorageClient**, etc., not `Object`. Then `getModel(...)`, `getVector(...)`, `getTool(...)`, `getStorage(...)` return those types without runtime casting. This is the only design smell to avoid: do not use `Object createRuntime(...)` in the public contract.

  **Thread safety:** Plugin runtime instances **must be thread-safe**. Cached runtimes are reused across workflow activities and concurrent pipeline executions. Plugins must ensure that the client returned by `createRuntime(...)` is safe for concurrent use or internally synchronized. Without this, you will eventually hit race conditions. Document this requirement in the plugin contract.

- Flow (orchestrated by ConnectionRuntimeManager):
  - ConnectionResolver produces **ResolvedConnection** (connectionId, tenantId, plugin, type, effectiveConfig).
  - ConnectionRuntimeManager gets plugin: `pluginRegistry.get(resolved.plugin())`.
  - `runtime = plugin.createRuntime(resolved.getEffectiveConfig());` — typed by connection/plugin type.

---

## 4. Strongly Typed Runtime Interfaces

**What:** Different plugin types expose typed runtime interfaces (e.g. `ModelClient`, `VectorClient`, `ToolClient`, `StorageClient`). **Avoid returning `Object`** from `createRuntime`; use these types so callers need no casting.

**How:**

- ConnectionRuntimeManager provides typed helpers:
  - `ModelClient getModel(String connectionName)`
  - `VectorClient getVector(String connectionName)`
  - `ToolClient getTool(String connectionName)`
  - `StorageClient getStorage(String connectionName)`
- Internally: resolve **ResolvedConnection** → plugin → `createRuntime(effectiveConfig)` returns the appropriate client type. No `(ModelClient) plugin.createRuntime(...)` at call sites.

---

## 4a. Plugin Connection Schema

**What:** Each plugin declares a **ConnectionSchema** describing its config fields (name, type, whether secret, optional/default). This enables UI form generation, validation before save, and plugin documentation.

**How:**

- Add to plugin interface: `ConnectionSchema schema();`

- Example (conceptual):

  ```java
  // OpenAIPlugin.schema()
  fields:
    baseUrl   (string, optional)
    apiKey    (secret)
    timeout   (number, optional)
  ```

- **Benefits:** UI can render connection forms per plugin; validation can run on create/update; docs can be generated from schema. Huge developer experience win.

---

## 5. Multi-Tenant Resolution

**What:** Connections must resolve **per tenant** (e.g. tenantA and tenantB can both use `openai-prod` but with different keys/config).

**How:**

- Connection lookup: `connectionRepo.get(tenantId, connectionName)` (done in ConnectionResolver).
- Enables true multi-tenant model/plugin usage; resolution always operates in tenant context.

---

## 6. Runtime Caching (Critical)

**What:** Creating clients repeatedly is expensive; cache runtime instances.

### 6a. ConnectionKey (Value Object)

Instead of passing `(tenantId, connectionName, version)` as separate arguments or string concatenation, use a **value object**:

```
ConnectionKey
-------------
tenantId
connectionName
version
```

**Cache:** `Map<ConnectionKey, Runtime>` (or `Cache<ConnectionKey, Runtime>` with Caffeine). Benefits: easier logging (log the key object), avoids string-concatenation keys and ordering bugs, reduces mistakes when building or comparing keys.

### 6b. Cache Implementation and Eviction Policy

**Implementation:** **Caffeine** (recommended). Avoid custom cache implementations.

**Eviction policy (define explicitly)** — without it, workers that resolve thousands of connections could grow indefinitely:

| Setting | Purpose |
|--------|---------|
| **maxSize** | Configurable upper bound (e.g. 1000 entries per worker). Prevents unbounded memory growth. |
| **expireAfterAccess** | Optional TTL after last access (e.g. 30 min). Evicts idle runtimes. |
| **Manual invalidation** | On specific events (see below). |

**Manual invalidation on:**

- **ConnectionUpdatedEvent** — Invalidate the entry for the affected `ConnectionKey` (tenantId, connectionName; any version for that connection can be evicted, or evict by key if event carries version).
- **SecretRotatedEvent** — Invalidate only runtimes that **reference the rotated secret** (see “Risks to Address Early” / secret rotation strategy). Do not invalidate all runtimes.

### 6c. Caching Flow (Diagram)

```
ConnectionRuntimeManager
        │
        ▼
  Runtime Cache
 (ConnectionKey: tenant + name + version)
        │
   cache.get(key)
        │
   cache hit?
     │     │
     yes   no
     │     ▼
     │  resolve connection
     │  create runtime
     │     cache.put(key, runtime)
     ▼
 return runtime
```

### 6d. Eviction and runtime cleanup

**What:** When a runtime is evicted from the cache (manual invalidation, size limit, or TTL), the Connection Manager **must** call `runtime.close()`. Otherwise long-running workers slowly leak resources (connections, threads, file handles).

**How:**

- **PluginRuntime** extends **AutoCloseable** (see [plugin-design](plugin-design.md)); implementations must release resources in `close()`.
- The cache uses a **removal listener** so that on every eviction the runtime is closed.

**Example with Caffeine:**

```java
removalListener((key, runtime, cause) -> {
    if (runtime != null) {
        runtime.close();
    }
})
```

Without this, evicted runtimes (e.g. after ConnectionUpdatedEvent or expireAfterAccess) would never be cleaned up, leading to resource leaks in multi-tenant, long-running workers.

---

## 7. Hot Reload Support

**What:** When connection config changes, existing runtimes must not use stale config.

**How:**

- On connection update:
  1. Update connection (DB/store) and **increment `version`**.
  2. Publish event (e.g. `ConnectionUpdatedEvent`).
  3. ConnectionRuntimeManager listens and **invalidates cache** for the affected **ConnectionKey** (tenantId, connectionName, old version); next request uses new version and creates a fresh runtime.
- Next use: lazy creation with new config and new version. No process restart required.

### 7a. Distributed cache invalidation

**What:** Events **ConnectionUpdatedEvent** and **SecretRotatedEvent** must be received **reliably by every worker**. If Olo runs multiple workers, cache invalidation must propagate to all of them; otherwise some workers keep using stale runtimes.

**How:**

- Ensure the event channel is **broadcast** (or fan-out) so every worker that caches runtimes receives the same events.
- **Typical implementations:**
  - **Message bus** — Kafka, RabbitMQ, or similar: publish invalidation events to a topic; each worker subscribes and invalidates its local cache on receipt.
  - **Redis pub/sub** — Publish `ConnectionUpdatedEvent(tenantId, connectionName, version)` (and similar for SecretRotatedEvent); workers subscribe and invalidate the corresponding ConnectionKey.
  - **Cluster-wide event layer** — If the worker already uses a cluster coordinator (e.g. Hazelcast, etcd), use its event or messaging API to broadcast invalidation to all members.
- Workers must **not** assume a single-process deployment; design for multiple workers from the start so cache invalidation is correct in production.

---

## 8. Secret Resolution Layer

**What:** Never expose raw secrets in config; resolve at runtime.

**How:**

- Connection has:
  - `config_json` (non-secret settings)
  - `secret_ref` (reference to secret manager, e.g. secret id or path)
- During runtime creation (in **SecretResolver**):
  1. Resolve secrets: `secretManager.get(secretRef)`.
  2. Merge into config: `config.put("apiKey", resolvedSecret)` (or equivalent).
  3. Pass merged config to `plugin.createRuntime(effectiveConfig)`.
- **SecretResolver** performs resolution; plugins receive a complete, non-raw config.

---

## 9. Pipeline Override Support

**What:** Pipelines can override connection-level settings (e.g. `temperature`, `model`).

**Example (pipeline/node config):**

```yaml
model: openai-prod
model_params:
  temperature: 0.2
```

**How:**

- At runtime: **merge** connection config and pipeline overrides.
  - `effectiveConfig = merge(connectionConfig, pipelineOverrides)`
- **ConnectionResolver** (or ExecutionContext) performs the merge; plugins see a single, effective config.

---

## 10. Lazy Initialization

**What:** Do not initialize all connections at startup.

**How:**

- Create runtime **only when first used**.
- Use a lazy cache: `cache.get(connectionKey)` → if absent, load connection, create runtime, cache, return.
- Keeps startup fast even with hundreds of connections.

---

## 11. Type Safety Validation

**What:** Connection type must match plugin type (e.g. connection type `MODEL` ↔ plugin type `MODEL`).

**How:**

- Validation during **connection create/update**: `plugin.type == connection.type`.
- Prevents misconfiguration (e.g. using a VECTOR_DB plugin for a connection typed as MODEL).

---

## 12. Connection Health Check

**What:** Ability to test a connection (e.g. for UI “Test connection” button).

**How:**

- Plugin exposes optional method: `boolean testConnection(ConnectionConfig config);`
- API: `POST /connections/{id}/test` (or equivalent) calls ConnectionRuntimeManager → ConnectionResolver → ConfigInterpolator → SecretResolver → `plugin.testConnection(config)` and returns success/failure.
- Useful for UI and ops.

---

## 13. Observability Hook

**What:** ConnectionRuntimeManager (and optionally ConnectionResolver / SecretResolver) should emit events for debugging and monitoring. Defining a **minimal event structure** keeps observability consistent across plugins and consumers.

**Minimal event shapes (example):**

| Event | Fields |
|-------|--------|
| **ConnectionResolvedEvent** | `tenantId`, `connectionName`, `plugin`, `durationMs` |
| **RuntimeCreatedEvent** | `tenantId`, `connectionName`, `plugin`, `runtimeType` |
| **ConnectionErrorEvent** | `tenantId`, `connectionName`, `plugin`, `errorType`, `message` (no secret values) |

**Examples of when to emit:**

- Connection resolved (after ConnectionResolver produces ResolvedConnection)
- Plugin runtime created (after createRuntime, before cache put)
- Connection error (e.g. creation failed, secret resolution failed)

**How:**

- Emit events (e.g. to existing execution event sink or a dedicated connection-events stream) so pipelines and tooling can observe connection lifecycle and failures.

### Latency breakdown (metrics)

In addition to events, break down **time spent** in each stage so operators can see where latency comes from. Example metrics:

| Metric | Meaning |
|--------|---------|
| `olo.connection.resolve.time` | Time in ConnectionResolver (load connection, merge overrides). |
| `olo.connection.secret.resolve.time` | Time in SecretResolver / ConfigInterpolator for this connection. |
| `olo.connection.runtime.create.time` | Time in `plugin.createRuntime(effectiveConfig)`. |
| `olo.connection.cache.hit` | Counter: resolution satisfied from cache. |
| `olo.connection.cache.miss` | Counter: resolution required createRuntime. |

Useful for tuning (e.g. slow runtime creation vs slow secret resolution) and SLOs.

---

## 14. Scoped Access (Security)

**What:** Not every pipeline (or team) should access every connection.

**Example:** teamA → `openai-prod`, teamB → `anthropic-prod`.

**How:**

- Table (or store): `connection_permissions` (e.g. tenant/scope/role → connection name or connection id).
- Before resolving: **check permission** for current tenant/scope/role; if not allowed, do not return runtime (or return error).
- ConnectionResolver or ConnectionRuntimeManager (or a policy layer above them) performs the check.

---

## 15. SDK Integration

**What:** Pipeline/SDK should not know plugin details.

**How:**

- Pipeline uses: `ctx.model("openai-prod")` (or similar) — API stays as-is.
- Internally: **ctx → ConnectionRuntimeManager → model runtime** (ConnectionRuntimeManager delegates to ConnectionResolver → ConfigInterpolator → SecretResolver → PluginRegistry).
- ConnectionRuntimeManager resolves (tenant, "openai-prod") → ModelClient; SDK stays clean and plugin-agnostic.

### Connection snapshot in ExecutionContext (safer)

**What:** Resolving by name only (e.g. `ctx.model("openai-prod")`) could, in theory, yield a different connection version if the connection is updated mid-run. **Using a snapshot is safer:** ExecutionContext stores a **connection snapshot** — a fixed mapping of connection name → version for the duration of the run.

**Example:**

```
connectionSnapshot:
  openai-prod → version 4
  pgvector-main → version 2
```

When the run (or execution context) is created, resolve the connection names that the pipeline/node uses and record their **versions** in the snapshot. Every later call during that run (e.g. `ctx.model("openai-prod")`) uses the snapshot: resolve by (tenantId, connectionName, **version from snapshot**). The same run always sees the same connection version; no mid-run change if an admin updates the connection. Behavior is consistent and predictable.

---

## 15a. Multi-connection use cases and plugin responsibility

**What:** Many plugins need **multiple connections of different types** in a single execution. The Connection Manager must **not** manage multi-connection orchestration; that responsibility belongs to the **plugin runtime** or **execution layer**.

### Example use cases

| Use case | Connections required |
|----------|------------------------|
| **RAG plugin** | Model connection + Vector DB connection |
| **SQL Agent plugin** | Model connection + Database connection |
| **Document processor** | Storage connection + Model connection |
| **ETL plugin** | Source DB + Destination DB |

In each case, the plugin needs several connections (often of different types). The plugin runtime resolves each dependency through the **ExecutionContext**.

### Correct pattern: plugin runtime resolves dependencies

The architecture already supports this. The plugin runtime receives **ExecutionContext** (or equivalent) and resolves additional resources by name. The Connection Manager only resolves **one connection per call**; the plugin orchestrates which connections it needs.

**Example contract:**

```java
interface ToolClient {
    ToolResult execute(ToolRequest request, ExecutionContext ctx);
}
```

**Example implementation (e.g. RAG tool):**

```java
public ToolResult execute(ToolRequest request, ExecutionContext ctx) {
    ModelClient model = ctx.model("openai-prod");
    VectorClient vector = ctx.vector("pgvector-main");

    List<Document> docs = vector.search(request.query());

    return model.generate(
        promptBuilder.build(request.query(), docs)
    );
}
```

The plugin calls `ctx.model(...)` and `ctx.vector(...)` as needed; each call goes to the Connection Manager for **that single connection**. The plugin composes the workflow; the Connection Manager stays unaware of multi-connection flows.

### Dependency graph

```
Pipeline
   │
   ▼
PluginRuntime (e.g. ToolClient)
   │
   ├── ctx.model("openai-prod")
   │        │
   │        ▼
   │  ConnectionRuntimeManager  (single connection)
   │
   └── ctx.vector("pgvector-main")
            │
            ▼
      ConnectionRuntimeManager  (single connection)
```

**Summary:**

- **Connection Manager:** Resolves and caches **single** connections. No knowledge of plugin workflows or dependency graphs.
- **Plugin runtime / execution layer:** Owns multi-connection orchestration; resolves each dependency via `ExecutionContext` (`ctx.model(name)`, `ctx.vector(name)`, `ctx.storage(name)`, etc.) as needed.

**Configuration-time validation:** For nodes that require multiple connections (e.g. RAG: model + vector), validate at pipeline/node config time that each referenced connection exists and has the correct type (e.g. `openai-prod.type == MODEL`, `pgvector-main.type == VECTOR_DB`). See §19.

---

## 16. Connection Types (Future Proofing)

**What:** Design types now to avoid refactoring later.

**Recommended types:**

- `MODEL`
- `VECTOR_DB`
- `DOCUMENT_STORE`
- `TOOL`
- `STORAGE`
- `AUTH`
- `CACHE`

(Extend as needed; validation in §11 ensures connection type matches plugin type.)

---

## 17. Fallback Support (Advanced)

**What:** If a connection fails, optionally use a fallback connection (e.g. `openai-prod` → fallback: `ollama-local`).

**How:**

- Connection (or pipeline) can define `fallback_connection` (name or id).
- **Define behavior explicitly** so fallback logic is consistent:
  - **Primary fails** → (optional) retry with defined **retry count** (e.g. 1).
  - **Error types that trigger fallback** — e.g. creation failure, timeout, auth error; document which errors cause fallback vs immediate fail.
  - **Fallback resolution** → resolve fallback connection and create its runtime (or reuse if fallback is also cached).
  - **Whether fallback runtime is cached** — e.g. cache fallback by (tenantId, fallbackConnectionName, version) like primary, so repeated use does not recreate.
- Example policy: **retry 1**; if still failure → resolve fallback and use its runtime; cache fallback runtime under its own key.
- Improves resilience without changing plugin code; clear spec avoids inconsistent behavior across workers.

---

## 18. Rate limit coordination

**What:** Many model providers enforce **per-API-key** rate limits. When multiple workers share the same connection (e.g. `openai-prod` for tenantA), aggregate traffic can easily exceed the provider’s limit.

**Example:** 20 workers, one connection `openai-prod` → without coordination, each worker sends requests independently and the shared API key hits the limit.

**Strategies:**

| Strategy | Description |
|----------|-------------|
| **Local limiter** | Per-worker rate limit. Simple but does not coordinate across workers; total traffic can still exceed provider limit. |
| **Distributed limiter** | Redis (or similar) as a shared rate-limit state. All workers consult the same bucket; respects provider limit across the fleet. |
| **Plugin-internal limiter** | Client library or plugin implements throttling. Works if the plugin is the only path to the provider; can be combined with distributed state. |
| **Token bucket per connection** | **Recommended.** One rate limiter (e.g. token bucket) per **ConnectionKey** (or per connection name + tenant). Registry: `ConnectionKey → limiter`. With distributed backend (e.g. Redis), the bucket is shared across workers. |

**Example:**

```
RateLimiterRegistry
   ↓
ConnectionKey → limiter (e.g. token bucket)
```

- Before calling the plugin runtime for a given connection, acquire a permit from the limiter for that ConnectionKey.
- Limits can be configured per connection (e.g. in connection config or tenant config) and aligned with provider tiers (e.g. 10k RPM for `openai-prod`).

---

## 19. Connection dependency validation (configuration time)

**What:** Plugins that require **multiple connections** (e.g. RAG: MODEL + VECTOR_DB) should be validated **at configuration time** so that wrong connection types are caught before execution.

**Example:** RAG node config:

```yaml
rag-node config:
  model: openai-prod
  vector: pgvector-main
```

**Validation:**

- Resolve connection `openai-prod` → require `connection.type == MODEL`.
- Resolve connection `pgvector-main` → require `connection.type == VECTOR_DB`.

If a user points `model` at a VECTOR_DB connection or `vector` at a MODEL connection, validation fails at pipeline/node config validation time with a clear error, instead of failing at runtime inside the plugin.

**How:**

- Pipeline or node schema declares required connection roles and types (e.g. `model: connection name, type MODEL`; `vector: connection name, type VECTOR_DB`).
- Validator (e.g. at deploy or save time) resolves each referenced connection and checks `resolvedConnection.getType()` matches the expected type for that role.
- Optionally: plugin descriptor can declare **required connection roles** (e.g. RAG plugin: `model → MODEL`, `vector → VECTOR_DB`); validation uses that to drive checks.

This prevents runtime failures and improves operator experience.

---

## 20. Connection usage tracking

**What:** Track **which pipelines and nodes use which connections**. Very helpful for operations: finding unused connections, safe deletion, usage analytics, and billing.

**Example:** Table (or equivalent) `connection_usage`:

| Field | Example |
|-------|---------|
| `tenant_id` | tenantA |
| `connection_name` | openai-prod |
| `pipeline` | rag-agent |
| `node` | retrieval-step |
| `last_used` | timestamp |

**How:**

- When a connection is resolved and used for execution (e.g. `ctx.model("openai-prod")` used by pipeline `rag-agent`, node `retrieval-step`), record or upsert a row: tenant, connection name, pipeline id/name, node id/name, last_used timestamp.
- Can be done in ConnectionRuntimeManager (on successful get) or in the execution layer when a node runs with a given connection.
- Keep writes lightweight (e.g. async, or batch) so resolution latency is not impacted.

**Benefits:**

- **Find unused connections** — Connections with no recent usage (or no rows) are candidates for removal or consolidation.
- **Safe deletion** — Before deleting a connection, check usage; warn or block if pipelines still reference it.
- **Usage analytics** — Dashboards and reports by tenant, connection, pipeline, node.
- **Billing insights** — Attribute usage to tenants, pipelines, or nodes for chargeback or quotas.

---

## 21. Connection aliases

**What:** **Aliases** map a stable name (used in pipelines) to a concrete connection name. Extremely useful for environments: switch the backing connection without changing pipeline config.

**Example:**

```
model-default   → openai-prod
vector-default → pgvector-main
```

**Pipeline:**

- `ctx.model("model-default")` — resolves via alias to `openai-prod`.

**Then switch alias (e.g. per environment or tenant):**

- `model-default → anthropic-prod`

No pipeline changes required; only the alias mapping changes. Enables dev/stage/prod or A/B switching by updating alias configuration.

**How:**

- Store alias mapping per tenant (e.g. `connection_aliases`: alias_name → connection_name). ConnectionResolver (or a thin layer above it) resolves alias → connection name first, then resolves the connection as usual. Connection snapshot (§15) can store alias → resolved connection version for the run.

---

## 22. Secret scope rules

**What:** Secrets can belong to different **scopes** (tenant, system, plugin). SecretResolver should support **scoped references** so multi-tenant security and isolation are clear.

**Example scopes:**

| Scope | Example use |
|-------|-------------|
| **tenant** | Tenant’s OpenAI key: `${secret:tenant:openai-key}` |
| **system** | Internal service key: `${secret:system:embedding-key}` |
| **plugin** | Plugin-shared key (e.g. shared across connections of same plugin): `${secret:plugin:openai-fallback}` |

**Syntax (example):**

- `${secret:tenant:openai-key}` — resolve in tenant scope.
- `${secret:system:embedding-key}` — resolve in system scope (no tenant in path, or system namespace).

Without scope, multi-tenant secret isolation and “system vs tenant” boundaries get tricky. See [secret-architecture](secret-architecture.md) for SecretResolver contract and SecretResolutionContext.

---

## 23. Runtime dependency graph (very advanced)

**What:** Pipelines often resolve the **same connections repeatedly** (e.g. Step 1, 2, 3 all call `ctx.model("openai-prod")`). Even with cache, repeated resolution calls add overhead. An **optimization** is to compute a **resource dependency graph** at pipeline start and **prefetch** required connections once.

**Example:**

- Step 1 → `ctx.model("openai-prod")`
- Step 2 → `ctx.model("openai-prod")`
- Step 3 → `ctx.model("openai-prod")`

**Optimization:**

At pipeline start:

```
ExecutionPlanner
     ↓
ResourceDependencyGraph
```

Example: pipeline dependencies = `openai-prod`, `pgvector-main`, `redis-cache`. Prefetch these once (resolve + createRuntime if needed, populate cache). Subsequent `ctx.model("openai-prod")` etc. are cache hits. Reduces redundant resolution and improves latency for multi-step runs.

---

## 24. Plugin isolation (security)

**What:** Community (or third-party) plugins can be dangerous. Isolation strategies reduce risk.

**Strategies:**

| Strategy | Description |
|----------|-------------|
| **Classloader isolation** | Load each plugin in a separate classloader so plugin code cannot see or mutate core classes. |
| **Permission sandbox** | Restrict FS, network, or other capabilities (e.g. Java SecurityManager or module boundaries). |
| **Resource quotas** | Limit memory and CPU time per plugin invocation or per runtime. |

These are already hinted at in the plugin docs; worth highlighting for security-sensitive deployments. See [plugin-design](plugin-design.md) and [plugin-discovery-and-loading](plugin-discovery-and-loading.md).

---

## 25. Connection soft delete / deprecation

**What:** Beyond **state** (ACTIVE, DISABLED, DEPRECATED), track **deprecation metadata** so the UI can warn users and suggest replacements.

**Fields (in addition to `state`):**

- **`deprecated_since`** — When the connection was marked deprecated (timestamp or version). UI: “Deprecated since 2024-01-15.”
- **`replacement_connection`** — Name (or id) of the recommended replacement. UI: “Use anthropic-prod instead.”

When resolving a connection in state `DEPRECATED`, emit a warning and optionally include `replacement_connection` in the message. Enables safe migration without breaking existing pipelines until they are updated.

---

## 26. Audit logging

**What:** **Audit logging** is very important for enterprise: security, compliance, and debugging. Emit audit events for connection and secret lifecycle and runtime creation.

**Example events:**

| Event | When |
|-------|------|
| `connection.created` | New connection created. |
| `connection.updated` | Connection config or metadata updated. |
| `connection.deleted` | Connection removed (or soft-deleted). |
| `secret.resolved` | Secret resolved for a connection/run (logical id + scope; no secret values). |
| `runtime.created` | Plugin runtime created (connection name, plugin, tenant; no config or secrets). |

**Useful for:**

- **Security** — Who changed which connection; which run used which secret.
- **Compliance** — Audit trail for SOC2, GDPR, etc.
- **Debugging** — Trace why a run used a given connection or failed after a config change.

Emit to a dedicated audit log or event stream; retain with appropriate retention and access controls.

---

## Risks to Address Early

**Risk 1 — Secret rotation**

Cache invalidation today is triggered by **ConnectionUpdatedEvent**. But **secrets may rotate independently** of connection config (e.g. vault or secret manager rotates a key). If workers do not invalidate runtimes that use that secret, they may continue using **old credentials**.

- **Add event:** `SecretRotatedEvent` (e.g. secret id or logical ref + tenant).
- **Behavior:** On **SecretRotatedEvent**, invalidate **only runtimes that reference that secret** (see strategies below). Do not invalidate all runtimes unnecessarily.

**How to identify affected connections/runtimes:**

| Strategy | Description |
|----------|-------------|
| **Option A** | Maintain a **secret → connection** mapping table (updated when connections are created/updated). On SecretRotatedEvent, look up connections that use that secret and invalidate their runtimes. |
| **Option B** | **Scan** all connections (or cached runtimes) and check which reference the rotated secret. Simpler but heavier. |
| **Option C (recommended)** | **SecretResolver (or ConfigInterpolator) registers runtime dependencies** when building effectiveConfig: e.g. store `runtime → Set<secretRef>` (or `ConnectionKey → secretRefs`). When a runtime is created, record which secret refs were used. On **SecretRotatedEvent(secretRef)**, invalidate only runtimes whose `secretRefs` contain that ref. This avoids invalidating all runtimes and avoids a separate mapping table. |

**Risk 2 — Connection failure behavior**

Fallback is defined above; ensure implementation agrees on retry count, error types that trigger fallback, and whether fallback is cached. Document in code or runbook so fallback behavior is consistent across workers and environments.

---

## Implementation Order (Suggested)

1. **Connection abstraction** — Table/schema and repository (`get(tenantId, connectionName)`). Include **version** column; increment on update.
2. **ConnectionResolver** — Resolve connection (and merge overrides) into **ResolvedConnection** (connectionId, tenantId, plugin, type, effectiveConfig); stateless, testable; no caching here.
3. **ConfigInterpolator** — Recursively walk config; resolve `${secret:...}` (and optionally `${env:...}`) via SecretResolver. Used by ConnectionResolver (or any config consumer).
4. **SecretResolver** — Resolve secrets (Secret Registry → provider → vault); invoked by ConfigInterpolator.
5. **Plugin registry integration** — Resolve plugin by `connection.plugin`; keep registry agnostic of connections.
6. **Runtime instance creation** — Plugin returns typed runtime (ModelClient, VectorClient, etc.), not Object; ConnectionRuntimeManager calls `createRuntime(resolvedConnection.getEffectiveConfig())` via ConnectionResolver → ConfigInterpolator → SecretResolver → PluginRegistry.
7. **Multi-tenant resolution** — All lookups by `(tenantId, connectionName)` in ConnectionResolver; output is ResolvedConnection.
8. **Runtime caching** — In ConnectionRuntimeManager: **ConnectionKey** value object (tenantId, connectionName, version); **Caffeine** cache `Cache<ConnectionKey, Runtime>` with explicit **eviction policy** (maxSize, optional expireAfterAccess, manual invalidation on ConnectionUpdatedEvent and SecretRotatedEvent). **Removal listener** must call `runtime.close()` on eviction (PluginRuntime extends AutoCloseable). Prevents unbounded growth, stale runtimes, and resource leaks.
9. **Strongly typed helpers** — ConnectionRuntimeManager: `getModel`, `getVector`, `getTool`, `getStorage` returning ModelClient, VectorClient, ToolClient, StorageClient (no casting).
10. **Pipeline override** — ConnectionResolver (or caller) merges pipeline overrides with connection config at resolution time.
11. **Hot reload** — ConnectionUpdatedEvent (with version bump) + ConnectionRuntimeManager invalidates cache; optionally **SecretRotatedEvent** to invalidate runtimes that used the rotated secret. **Distributed invalidation**: ensure all workers receive these events (message bus, Redis pub/sub, or cluster event layer); see §7a.
12. **Connection schema** — Plugins expose `ConnectionSchema schema()` for UI, validation, docs.
13. **Type validation** — On connection create/update, enforce `plugin.type == connection.type`.
14. **Health check** — Optional `testConnection` on plugin; expose `POST /connections/{id}/test` (ConnectionRuntimeManager → ConnectionResolver → ConfigInterpolator → SecretResolver → plugin).
15. **Observability** — Emit **ConnectionResolvedEvent**, **RuntimeCreatedEvent**, **ConnectionErrorEvent** (with minimal fields: tenantId, connectionName, plugin, durationMs / runtimeType / errorType) from ConnectionRuntimeManager (and optionally resolvers).
16. **Scoped access** — `connection_permissions` and check in ConnectionResolver (or policy layer) before resolve.
17. **SDK integration** — Execution context exposes `model(name)`, `vector(name)`, etc., backed by ConnectionRuntimeManager.
18. **Fallback** — Optional fallback connection; define retry count, error types that trigger fallback, and whether fallback runtime is cached; implement consistently.
19. **Rate limit coordination** — RateLimiterRegistry keyed by ConnectionKey (token bucket per connection); optional distributed backend (e.g. Redis) when multiple workers share connections; see §18.
20. **Connection dependency validation** — At pipeline/node config validation time, validate that referenced connections exist and have the expected type (e.g. RAG: model → MODEL, vector → VECTOR_DB); see §19.
21. **Connection usage tracking** — Record connection_usage (tenant, connection_name, pipeline, node, last_used) for unused-connection detection, safe deletion, analytics, billing; see §20.
22. **Connection aliases** — Alias mapping (alias_name → connection_name) per tenant; ConnectionResolver resolves alias first. Enables environment switching without pipeline changes; see §21.
23. **Secret scope rules** — SecretResolver supports `${secret:tenant:...}`, `${secret:system:...}`, `${secret:plugin:...}`; see §22 and [secret-architecture](secret-architecture.md).
24. **Observability latency breakdown** — Emit metrics: resolve time, secret resolve time, runtime create time, cache hit/miss; see §13.
25. **Runtime dependency graph (advanced)** — Optional: ExecutionPlanner → ResourceDependencyGraph; prefetch connections at pipeline start; see §23.
26. **Connection soft delete / deprecation** — Fields deprecated_since, replacement_connection; UI warnings; see §1 (fields) and §25.
27. **Audit logging** — Emit connection.created/updated/deleted, secret.resolved, runtime.created for security, compliance, debugging; see §26.

---

## Strategic direction: Unified resource architecture

The current design (ConnectionRuntimeManager, ConnectionResolver, SecretResolver, PluginRegistry) works well for **connections**. The same pattern will soon be needed for **prompts**, **datasets**, **tools**, **pipelines**, **templates**, and **policies**. If each gets its own manager and resolver, complexity explodes:

- ConnectionRuntimeManager
- PromptRuntimeManager
- DatasetRuntimeManager
- ToolRuntimeManager
- PipelineRuntimeManager
- …

**Simplification:** Turn connections into a generic **Resource**. One manager, one resolver, one cache — same architecture for every resource type. Existing connection rows become resources with `type = MODEL` or `type = VECTOR_DB`; nothing is lost.

---

### 1. Replace "Connection" with "Resource"

Use a single generic concept:

| Resource type | Example name |
|---------------|---------------|
| MODEL | openai-prod |
| VECTOR_DB | pgvector-main |
| PROMPT | support-agent |
| DATASET | docs-index |
| TOOL | web-search |
| PIPELINE | rag-agent |
| STORAGE | s3-main |

Existing connection rows become resources with `type = MODEL`, `type = VECTOR_DB`, etc. The abstraction generalizes; the data model stays compatible.

---

### 2. Replace ConnectionRuntimeManager with ResourceRuntimeManager

One manager for all resource types. Responsibilities stay the same: resolve resource, resolve secrets, merge overrides, create runtime, cache runtime.

**Architecture:**

```
ExecutionContext
      │
      ▼
ResourceRuntimeManager
      │
      ▼
ResourceResolver
      │
      ▼
ConfigInterpolator
      │
      ▼
SecretResolver
      │
      ▼
PluginRegistry
      │
      ▼
PluginRuntime
```

Same pipeline as today — generalized to **Resource**.

---

### 3. ResourceKey replaces ConnectionKey

**Before:** `ConnectionKey(tenantId, connectionName, version)`  
**After:** `ResourceKey(tenantId, resourceType, resourceName, version)`  
**Cache:** `Cache<ResourceKey, Runtime>` — works for all resource types; no per-type cache.

---

### 4. ExecutionContext becomes a resource gateway

All resolution goes through the same manager:

- `ctx.model("openai-prod")` → `ResourceRuntimeManager.resolve(MODEL, "openai-prod")`
- `ctx.vector("pgvector-main")` → `ResourceRuntimeManager.resolve(VECTOR_DB, "pgvector-main")`
- `ctx.prompt("support-agent")`
- `ctx.dataset("docs-index")`
- `ctx.tool("web-search")`

Same resolution path, caching, secrets, and overrides for every resource type.

---

### 5. Plugins become resource providers

Plugins already implement `createRuntime(config)` and are registered by type. Example mapping: OpenAIPlugin → MODEL, PgVectorPlugin → VECTOR_DB, RedisPlugin → CACHE, S3Plugin → STORAGE. No contract change.

---

### 6. Prompts and datasets plug in naturally

**Prompt resource:** `type: PROMPT`, `name: support-agent`; config (template, variables); runtime `PromptRuntime`.  
**Dataset resource:** `type: DATASET`, `name: docs-index`; config (vector: pgvector-main, index); runtime `DatasetClient`. Same ResourceResolver, same cache, same ResourceRuntimeManager.

---

### 7. The database table becomes "resources"

**Before:** table `connections`  
**After:** table `resources`

Example: `id | tenant | type | name | plugin | config` — e.g. (1, tenantA, MODEL, openai-prod, openai, {...}), (2, tenantA, VECTOR_DB, pgvector-main, pgvector, {...}), (3, tenantA, PROMPT, support-agent, prompt-template, {...}). Existing connection rows migrate with same type/name/plugin/config; columns like version, state, deprecated_since apply to all. This unlocks prompts, datasets, tools, pipelines, templates, and policies without adding a new manager per concept.

**Optional future: resource addressing** — e.g. `resource://model/openai-prod`, `resource://dataset/docs-index`. Not required for first implementation.

---

The Connection Manager (ConnectionRuntimeManager + ConnectionResolver + SecretResolver + PluginRegistry) is the first concrete implementation of this pattern; generalizing it to **ResourceRuntimeManager** and a **resources** table scales the same architecture to prompts, datasets, tools, pipelines, templates, and policies without exploding complexity.

(Execution context exposes resources by name; see §4 above.)
---

## References

- **Plugin contract:** [Plugin design](plugin-design.md) — OloPlugin interface (name, type, schema, createRuntime), ConnectionSchema, typed runtimes, thread safety, PluginRegistry.
- **Secret system:** [Secret architecture](secret-architecture.md) — ConfigInterpolator, SecretResolver, Secret Registry, SecretProvider plugins, vault-agnostic references, structured secrets, value-based masking; used when building effective config for connections, tools, pipelines.
- Existing worker: **PluginRegistry**, **PluginExecutor**, tenant-scoped config.
- Execution context: **NodeExecutionContext**, **VariableEngine** (where pipeline overrides may live).
- Ledger/events: **ExecutionEventSink** (for observability).
- Config: **TenantConfigRegistry**, **OloConfig** (tenant id, defaults).

This document should live in version control and be updated as the Connection Manager is implemented.
