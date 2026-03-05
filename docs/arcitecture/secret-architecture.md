# Olo Secret Architecture — Design Specification

The secret system for Olo is **plugin-based**, **tenant-aware**, **runtime-resolvable**, and **secure by design**. It integrates with the existing **Connection Manager**, **ConnectionResolver**, and **multi-tenant pipelines** rather than acting as a separate service. The design is **vault-agnostic**: pipelines reference logical secrets; provider plugins map those to any credential vault interchangeably.

---

## Goals

A good secret architecture for Olo must support:

| Goal | Description |
|------|-------------|
| Multiple secret backends | Vault, AWS Secrets Manager, env, DB, GCP, Azure — via plugins |
| Multi-tenant isolation | Secrets scoped per tenant; resolver injects tenant context |
| Plugin independence | Plugins never read vaults directly; they receive resolved config |
| Secret rotation without restart | Runtime resolution + TTL cache; next fetch gets new value |
| Runtime resolution | Secrets resolved during execution, not at deploy time |
| Works with Connection Manager | ConfigInterpolator (which uses SecretResolver) is a generic utility; secrets can appear in connections, tool configs, pipeline configs, RAG configs ([connection-manager-design](connection-manager-design.md)) |
| Vault interchangeability | Same pipeline config works with any vault; switch provider via Secret Registry |

---

## High-Level Architecture

**SecretResolver is a lower-level, generic utility** — not tied only to connections. Secrets may appear in connection configs, tool configs, pipeline configs, model configs, and RAG configs. So the layering uses a **ConfigInterpolator** that delegates to SecretResolver (and optionally `${env:*}` / `${runtime:*}`). Airflow and Terraform follow this pattern.

```
Pipeline
   ↓
ExecutionContext
   ↓
ConnectionRuntimeManager (or other config consumers)
   ↓
ConnectionResolver
   ↓
ConfigInterpolator
   ↓
SecretResolver
   ↓
Secret Provider (Plugin)
   ↓
Credential Vault
```

- **ConnectionResolver** (or any caller) loads raw config containing `${secret:...}` (and optionally `${env:...}`, `${runtime:...}`).
- **ConfigInterpolator** walks the config structure recursively, finds placeholders, and calls **SecretResolver** (or env/runtime resolvers) for each.
- **SecretResolver** parses references, consults Secret Registry, calls the appropriate **Secret Provider**; never talks to ConnectionResolver directly.

**Connection + secret resolution flow (summary):** Pipeline → ConnectionRuntimeManager → ConnectionResolver → ConfigInterpolator → SecretResolver → SecretRegistry → SecretProvider → Vault/AWS/Env. Resolved config then feeds **createRuntime()** → cached PluginRuntime. See [connection-manager-design](connection-manager-design.md) §Connection and secret resolution flow.

**Example providers:**

- `secret-vault` — HashiCorp Vault
- `secret-aws` — AWS Secrets Manager
- `secret-env` — Environment variables (local dev)
- `secret-db` — Database-backed (SaaS)
- `secret-gcp` — Google Secret Manager
- `secret-azure` — Azure Key Vault
- `secret-k8s` — Kubernetes Secrets

Secrets are pluggable like tools; each vault is a plugin.

---

## Core Idea: Vault-Agnostic References

**Never store secrets directly. Never tie config to a specific vault.**

Instead:

1. **Logical secret references** in config (e.g. `${secret:openai.api_key}`).
2. **Secret Registry** (or Secret Metadata Store) maps logical id → provider + path/key (vault-specific). “Registry” implies dynamic; “Catalog” sounds static — prefer **Secret Registry**.
3. **Secret Provider plugins** adapt to each vault.

Pipeline config stays **portable**; switching vaults requires only registry changes, no pipeline changes.

```
Pipeline Config (vault-agnostic)
      ↓
Secret Reference (logical)
      ↓
SecretResolver
      ↓
Secret Registry (mapping)
      ↓
Secret Provider Adapter
      ↓
Credential Vault
```

---

## 1. Secret Reference Model

**Format:** logical id only (recommended for vault interchangeability).

```
${secret:<logical_id>}
```

**Examples:**

- `${secret:openai.api_key}`
- `${secret:db.prod.password}`
- `${secret:db.prod.username}` (subfield of a structured secret; see §2a)
- `${secret:slack.bot_token}`

These are **logical secret IDs**. No vault name or path in the pipeline.

**Internal representation:** parse into a typed value for validation, caching, and audit logs:

```java
class SecretReference {
    String logicalId;   // e.g. "openai.api_key" or "db.prod.password"
}
```

**Optional extended format** (for advanced use or migration):

```
${secret:<provider>:<path>}
```

Examples: `${secret:vault:kv/data/openai#api_key}`, `${secret:env:OPENAI_API_KEY}`. Prefer logical ids for portability.

### Secret scope (tenant, system, plugin)

Secrets can belong to different **scopes**. SecretResolver should support **scoped references** so multi-tenant isolation and system vs tenant boundaries are clear:

| Scope | Example | Use |
|-------|---------|-----|
| **tenant** | `${secret:tenant:openai-key}` | Tenant’s OpenAI key; resolved in tenant namespace. |
| **system** | `${secret:system:embedding-key}` | Internal service key; no tenant in path (system namespace). |
| **plugin** | `${secret:plugin:openai-fallback}` | Plugin-shared secret (e.g. shared across connections of same plugin). |

**Syntax:** `${secret:<scope>:<logical_id>}`. SecretResolutionContext supplies tenantId (and optionally scope); registry and providers resolve according to scope. Without scope, multi-tenant security and “system vs tenant” separation get tricky. See also [connection-manager-design §22](connection-manager-design.md#22-secret-scope-rules).

---

## 2. Secret Registry (Mapping Layer)

Olo maintains a **Secret Registry** (or **Secret Metadata Store**) that maps logical ids to vault-specific locations. “Registry” implies dynamic lookup; “Catalog” is sometimes used for static config — prefer **Secret Registry** for consistency with other Olo components.

**Example:**

```yaml
secrets:
  openai.api_key:
    provider: vault
    path: kv/data/openai
    key: api_key

  db.prod:
    provider: vault
    path: kv/data/db/prod
    # no key: whole object (username, password, etc.)
```

**Switching vaults:** change only the registry; pipelines stay unchanged.

```yaml
secrets:
  openai.api_key:
    provider: aws
    path: prod/openai
    key: api_key
```

Registry can be stored in DB, config file, or config service; loaded per tenant or globally with tenant overrides.

### 2a. Registry Hierarchy (Optional)

Large systems need inheritance. Resolution order (first match wins):

1. **Tenant** registry (tenant overrides)
2. **Environment** registry (e.g. staging, prod)
3. **Global** (system) registry

Example: tenant registry → environment registry → global registry. This allows global defaults with tenant-specific overrides without duplicating entries.

---

## 3. Structured Secrets (Multi-Field Credentials)

Many credentials are **multi-field**, not single strings.

| Credential type | Fields |
|-----------------|--------|
| Database | username, password |
| OAuth | client_id, client_secret |
| AWS | access_key, secret_key |
| API | api_key, org |

Vault may store a single object, e.g. `{"username": "db_user", "password": "secret"}`. Some vaults also return **non-string values**: `port: 5432`, `ttl: 3600`, `enabled: true`. A descriptor with only `String key` and a value type of only `String` is limiting. **Allow structured secrets with flexible value types.**

```java
public class SecretValue {
    Map<String, Object> values;   // Object allows String, Number, Boolean, etc.

    Object get(String key) {
        return values.get(key);
    }
    // For config injection, coerce to string where needed. Single-field: one entry (e.g. key "value" or the logical key).
}
```

Using **`Map<String, Object>`** gives future flexibility (ports, TTLs, flags) without changing the model later.

**Plugin config can reference subfields:**

```yaml
config:
  username: ${secret:db.prod.username}
  password: ${secret:db.prod.password}
```

**Or reference the whole secret** (one vault call, then subfield lookup from cache):

```yaml
config:
  db: ${secret:db.prod}
```

Resolver: resolve `db.prod` once → get `SecretValue` with `username` and `password`; for `${secret:db.prod.password}` return `secretValue.get("password")` (coerce to string for config injection if needed). This avoids multiple vault calls for the same logical secret.

---

## 4. Secret Descriptor (Vault-Neutral)

The resolver uses a **vault-neutral** descriptor when calling a provider.

```java
public class SecretDescriptor {
    String id;        // openai.api_key or db.prod (logical)
    String provider;  // vault | aws | env | ...
    String path;      // vault-specific path (after tenant substitution)
    String key;       // optional key within path or within returned SecretValue
}
```

Providers receive `SecretDescriptor` + **SecretResolutionContext** (see below); they return **SecretValue**. Resolver may then return one subfield for a reference like `${secret:db.prod.password}`.

---

## 4a. Secret Resolution Context

Resolution may depend on more than tenant. Secrets can be scoped by **tenant**, **environment**, **pipeline**, **run**, **tool**, **connection**. Instead of passing only `TenantContext`, define a **SecretResolutionContext** and pass it to the resolver and providers.

```java
class SecretResolutionContext {
    String tenantId;
    String environment;    // e.g. staging, prod
    String pipelineId;
    String connectionId;
    String toolId;
    String runId;
}
```

**Provider API becomes:**

```java
SecretValue resolve(
    SecretDescriptor descriptor,
    SecretResolutionContext context
);
```

**Benefits:**

- **Enables RBAC later** — e.g. allow secret only for a given pipeline or tool.
- **Run-level secret policies** — restrict which run can access which secret.
- **Context-aware vault paths** — path templates can include any context field.

**Example path template in registry:**

```yaml
openai.api_key:
  provider: vault
  path: kv/olo/{tenant}/{pipeline}/openai
  key: api_key
```

At runtime, resolver (or provider) substitutes `context.tenantId`, `context.pipelineId`, etc., so each tenant/pipeline gets the correct path. Callers (ConfigInterpolator, ConnectionResolver) build **SecretResolutionContext** from ExecutionContext or equivalent.

---

## 5. Secret Provider Interface

Each backend implements a common interface and is loaded via the plugin registry. It returns **SecretValue** (structured: `Map<String, Object>` + `get(key)`), so multi-field credentials and non-string values (e.g. port, ttl, enabled) are supported.

```java
public interface SecretProvider {
    String name();   // e.g. "vault", "aws", "env"

    SecretValue resolve(
        SecretDescriptor descriptor,
        SecretResolutionContext context
    );

    // Optional: for operational debugging (see §5a)
    default HealthStatus health() { return HealthStatus.unknown(); }
}
```

**Example implementations:**

- `VaultSecretProvider`
- `AwsSecretsProvider`
- `EnvSecretProvider`
- `DatabaseSecretProvider`

Olo discovers them through the same plugin/registry mechanism used for other plugins.

### 5a. Secret Provider Health Check (Optional)

Providers can optionally support health checks for operational debugging.

```java
HealthStatus health();  // e.g. healthy, unhealthy, unknown
```

Workers can expose an endpoint such as **`/health/secrets`** that reports per-provider status, e.g.:

- vault: healthy  
- aws: healthy  
- env: healthy  

This improves detection of vault connectivity issues without running a full resolution.

---

## 6. Secret Resolver (Core Component)

**SecretResolver is independent of ConnectionResolver.** It is a lower-level utility invoked by **ConfigInterpolator** (or any caller that has config with `${secret:...}`). ConfigInterpolator can also handle `${env:...}`, `${runtime:...}` — same pattern as Airflow/Terraform.

**Responsibilities:**

1. Parse `${secret:...}` into **SecretReference** (logicalId).
2. Look up logical id in **Secret Registry** (with hierarchy: tenant → environment → global) → obtain `SecretDescriptor`.
3. Select **SecretProvider** by `descriptor.provider`.
4. Build **SecretResolutionContext** (tenantId, environment, pipelineId, connectionId, toolId, runId) from caller context; apply path templating (e.g. `{tenant}`, `{pipeline}`) to descriptor path.
5. Call `provider.resolve(descriptor, context)`; get **SecretValue**.
6. Cache result with key derived from **(provider, normalizedPath, key, tenant)** — see §9 (descriptor normalization).
7. Return resolved string (or subfield from SecretValue for references like `db.prod.password`).

Plugins **never** see `${secret:...}`; they receive the final, resolved config only.

### 6a. Recursive Resolution (Deep Config)

Config is often nested: maps, lists, nested objects. The **resolver must walk the full structure**, not only top-level keys.

**Algorithm (ConfigInterpolator or SecretResolver):**

```
resolve(object):
  if object is string and matches ${secret:*} (or ${env:*}, etc.)
      resolve secret (or env) and return replacement string
  if object is map
      for each value: value = resolve(value)
  if object is list
      for each item: item = resolve(item)
  return object
```

### 6b. Batch References (One Vault Read per Logical Secret)

To prevent unnecessary vault calls, **do not resolve each reference independently**. Instead:

**Step 1 — Scan:** Walk the config recursively and **collect all** `${secret:...}` references (e.g. list of `SecretReference` or logical ids).

**Step 2 — Group by logical id:** Group references by the **logical secret id** (e.g. `db.prod` for both `db.prod.username` and `db.prod.password`). Subfield references (`db.prod.username`) map to the same logical secret `db.prod`.

**Step 3 — Resolve each secret once:** For each distinct logical id, call the provider **once** and cache the **SecretValue**. Then, for every reference (including subfields), obtain the value from that cached result. Example: `${secret:db.prod.username}` and `${secret:db.prod.password}` → **one** vault read for `db.prod`; return `secretValue.get("username")` and `secretValue.get("password")` from the same cached object.

Without batching, multiple references to the same secret (or its subfields) would trigger multiple vault reads.

### 6c. Secret Prefetching (Major Performance Improvement)

Instead of resolving during interpolation (one reference at a time), **prefetch all needed secrets first**, then interpolate from cache. This reduces vault latency spikes by batching provider calls before any value substitution.

**Flow:**

1. **Scan config** — Walk config recursively and collect all `${secret:...}` references (same as §6b Step 1).
2. **Group by logical id** — Deduplicate to distinct logical ids (same as §6b Step 2).
3. **Prefetch** — Call **`SecretResolver.prefetch(logicalIds, context)`** (or equivalent). Resolver resolves each logical id once, populates cache, returns when all are loaded (or fails fast on first error).
4. **Interpolate** — Walk config again and replace each reference with the value from cache. No provider calls during this phase.

```
ConfigInterpolator
      ↓
collect references (scan + group by logical id)
      ↓
SecretResolver.prefetch(ids, context)
      ↓
populate cache
      ↓
interpolate values (from cache only)
```

**Example:** `${secret:db.prod.username}`, `${secret:db.prod.password}`, `${secret:openai.api_key}` → prefetch `db.prod` and `openai.api_key` (two provider calls), then interpolate all three references from cache. This avoids three separate resolve calls and spreads vault load up front.

**Recursive walk (from §6a):** Without walking maps and lists recursively, configs like the following would break:

```yaml
config:
  openai:
    key: ${secret:openai.api_key}
  database:
    password: ${secret:db.prod.password}
```

Resolution walks maps and lists recursively so every string value is checked for placeholders.

---

## 7. Multi-Tenant Secret Scoping

Secrets must be isolated per tenant.

**Path resolution example:**

- `tenantA` → `tenants/tenantA/openai` (or tenant-scoped key).
- `tenantB` → `tenants/tenantB/openai`.

**Registry entry with tenant placeholder:**

```yaml
openai.api_key:
  provider: vault
  path: kv/olo/tenants/{tenant}/openai
  key: api_key
```

**Runtime:** SecretResolver (or provider) injects `tenant` (and optionally `environment`, `pipeline`, etc.) from **SecretResolutionContext** so each tenant gets its own secret path/key. No cross-tenant leakage.

---

## 8. Secret Injection in Plugins

Plugins **never** read secrets directly. They receive **resolved** configuration.

**Example connection/plugin config (with reference):**

```yaml
plugin: openai
config:
  model: gpt-4
  api_key: ${secret:openai.api_key}
```

**Runtime flow (with prefetch):**

1. ConnectionResolver loads connection config (with `${secret:...}`).
2. ConfigInterpolator **scans** config and collects all secret references; **prefetches** them via `SecretResolver.prefetch(ids, context)` so cache is populated.
3. ConfigInterpolator **interpolates**: replace each reference with value from cache.
4. Pass **final config** (no `${secret:...}` left) to plugin.
5. Plugin executes with resolved values only.

Plugin code never sees `${secret:...}` or vault APIs.

---

## 9. Secret Caching Layer

**Purpose:** avoid hitting the vault on every run; support high QPS.

```
SecretResolver
     ↓
Secret Cache (TTL)
     ↓
Secret Provider
     ↓
Vault
```

**Thread safety:** Workers are concurrent. Use a **thread-safe cache**. **Recommended: Caffeine.** Avoid custom cache implementations; Caffeine provides TTL, concurrency, and metrics support out of the box.

**Cache key:** Do not use only `(logical_id, tenant)`. Use a key that reflects the **resolved, normalized** descriptor:

- **Key:** `(provider, normalizedPath, key, tenant)`.

**Descriptor normalization:** Before cache lookup (and before calling the provider), **normalize** the descriptor path by substituting all placeholders from **SecretResolutionContext** (e.g. `{tenant}` → `tenantA`, `{pipeline}` → `chat`). Example: `kv/olo/tenants/{tenant}/openai` → `kv/olo/tenants/tenantA/openai`. Use this **normalized path** in the cache key. This prevents subtle cache duplication bugs (e.g. same logical secret cached under both templated and non-templated keys).

**Registry update behavior:** When the **Secret Registry** is updated (e.g. path or provider change for a logical id):

- **Invalidate affected cache entries** (e.g. any cache key that was derived from the updated registry entry). Next resolve fetches from the provider with the new descriptor. This prevents **stale secrets** after a registry change.

**Cache strategy:**

- **TTL cache** — entries expire after a configured TTL (e.g. 60s); next resolve fetches from vault (supports rotation).
- **Optional refresh** — refresh on access when near expiry.
- **Optional invalidation** — on registry update (as above) or vault webhook, invalidate affected keys.

**Future:** Async refresh in background so the first request after expiry does not pay latency.

**Rate limiting (optional):** Vault outages can cascade if many workers retry simultaneously. Add an optional **per-provider rate limit** (e.g. vault resolve limit = 100/sec). This protects the vault from thundering herd problems. Implement at SecretResolver or provider adapter layer.

---

## 10. Secret Rotation

Because secrets are **resolved at runtime** and cache has TTL:

1. Vault rotates key (or admin updates secret).
2. Cache expires (or is invalidated).
3. Next run triggers resolve → provider fetches **new** secret.
4. No process restart required.

Optional: webhook or event from vault to invalidate cache for that secret.

---

## 11. Secret Masking

Secrets must **never** appear in:

- Logs (worker, activity, workflow)
- UI (run history, debug panels)
- Execution events
- Temporal history

**Do not rely on key names.** Keys like `openai_key`, `service_auth`, or custom names will be missed if the mask filter only looks for `password`, `token`, etc. — unsafe.

**Value-based masking (recommended):**

1. **Track resolved secret values during resolution.** When SecretResolver (or ConfigInterpolator) replaces a `${secret:...}` with a value, add that value to a **resolved secrets set** (e.g. `resolvedSecrets.add(secretValue)`), scoped to the current run or request.
2. **Masking layer** replaces **exact matches** of any string in that set with a placeholder (e.g. `********`) before writing to log, event, or UI.

Example: resolved value `sk-12345abc` → any log line containing that exact string becomes `api_key=********` (or the whole value replaced). This avoids false negatives for non-standard key names.

---

```
Execution Logger / Event Emitter
     ↓
Secret Mask Filter (value-based: replace any resolved secret value)
     ↓
Log / UI / History
```

Apply at emission time so raw secrets never leave the process.

**Secret redaction in UI:** Masking covers logs and events; **UI debugging tools** (execution view, config inspector, run history) must also never show resolved secret values. Example: execution view should display `config.api_key: ********`, not the actual value. Apply the same value-based mask filter (or a dedicated UI redaction layer) before rendering any config or state that might contain secrets.

---

## 12. Secret Provider Plugins (Examples)

| Provider | Reference example | Use case |
|----------|-------------------|----------|
| Env | Registry: `provider: env`, path/key → env var | Local dev |
| Vault | Registry: `provider: vault`, path/key | Enterprise production |
| AWS | Registry: `provider: aws`, path/key | Cloud / AWS |
| DB | Registry: `provider: db`, path/key | SaaS, app-managed secrets |
| GCP / Azure | Registry: `provider: gcp` / `azure` | Multi-cloud |

Each is a plugin; Olo can ship with **env** and **vault** as defaults and add others later.

---

## 13. Full Runtime Flow

1. User triggers pipeline.
2. ExecutionContext created (tenant, run, etc.).
3. ConnectionRuntimeManager needs runtime for a connection (e.g. `openai-prod`).
4. ConnectionResolver loads connection config (includes `api_key: ${secret:openai.api_key}`).
5. ConfigInterpolator collects secret references, then **prefetches** via SecretResolver.prefetch(context); then interpolates (replaces references from cache). Track resolved values for masking.
7. Secrets injected into config (references replaced by values).
8. ConnectionRuntimeManager passes resolved config to plugin; plugin creates runtime (e.g. OpenAI client).
9. Plugin executes; it never sees raw references or vault.

---

## 14. Recommended Folder / Module Structure

```
olo-core (or olo-worker-core)
   config/
      ConfigInterpolator
   secrets/
      SecretResolver
      SecretRegistry
      SecretParser
      SecretReference
      SecretDescriptor
      SecretResolutionContext
      SecretProvider (interface)
      SecretValue
      TenantContext (or use existing; context can extend or wrap it)

olo-plugin-secret-env
olo-plugin-secret-vault
olo-plugin-secret-aws
olo-plugin-secret-db
olo-plugin-secret-gcp
olo-plugin-secret-azure
```

Each vault lives in its own plugin module; core only defines interface and resolver.

---

## 15. Example Config in Olo

**Connection / plugin config (user-facing):**

```yaml
plugin: openai
config:
  model: gpt-4
  api_key: ${secret:openai.api_key}
```

**Database connection:**

```yaml
plugin: postgres
config:
  host: db.internal
  password: ${secret:db.prod.password}
```

**Secret Registry (admin / config):**

```yaml
secrets:
  openai.api_key:
    provider: vault
    path: kv/olo/tenants/{tenant}/openai
    key: api_key
  db.prod.password:
    provider: aws
    path: prod/database
    key: password
```

Switching from Vault to AWS for `openai.api_key` = change only registry; no pipeline or connection config change.

---

## 16. Why This Fits Olo

- **Plugin architecture** — Secret backends are plugins like any other.
- **Multi-tenant** — SecretResolutionContext (tenantId, environment, pipeline, run, tool, connection) and path templating give isolation and future RBAC.
- **Connection Manager** — ConfigInterpolator (using SecretResolver) is the generic layer in [connection-manager-design](connection-manager-design.md); ConnectionResolver (or any config consumer) uses it when building effective config for connections, tools, pipelines, etc.
- **Temporal workers** — Resolution happens in worker; no secret in workflow code or history if masking is applied.
- **Runtime resolution** — Enables rotation and dynamic secrets (future).
- **Vault-agnostic** — Logical references + Secret Registry + provider adapters allow any credential vault to be used interchangeably.

---

## 17. V1 Recommendation

For Olo v1, implement:

| Component | Purpose |
|-----------|---------|
| **ConfigInterpolator** | Recursively walk config; resolve `${secret:...}` (and optionally `${env:...}`) via SecretResolver |
| **SecretResolver** | Parse references, look up Secret Registry, normalize descriptor, call provider (with SecretResolutionContext); support prefetch and batch resolution |
| **Secret Registry** | Map logical id → provider + path + key (e.g. config or DB); optional hierarchy (tenant → env → global) |
| **SecretProvider** interface | Contract for all backends |
| **EnvSecretProvider** | Resolve from environment (local dev) |
| **VaultSecretProvider** | HashiCorp Vault (production) |
| **Secret masking** | Value-based: track resolved values during resolution; mask exact matches in logs, events, UI |

Add AWS, GCP, DB, Azure providers and advanced features (dynamic secrets, scoped tokens, audit logs) in later versions.

---

## 18. Optional Future Enhancements

- **Credential objects** — Return typed `ApiCredential`, `DatabaseCredential` instead of raw strings; plugins receive structured types.
- **Dynamic secrets** — e.g. `${secret:db.dynamic}`; vault issues short-lived credentials (e.g. TTL 1h); great for DB and tool security.
- **Secret UI** — CRUD for registry, test connection, rotation triggers.
- **Secret rotation API** — Trigger rotation and cache invalidation.
- **Run-level secret isolation / RBAC** — SecretResolutionContext (pipeline, run, tool, connection) enables policy checks; e.g. `${secret:tool.github.token}` with tool/tenant/pipeline permission. Almost no open-source AI runtime does this well; Olo’s architecture supports it.
- **Secret access auditing (enterprise)** — Emit **secret.access** events with: secretId, tenantId, pipelineId, runId, provider. Essential for SOC2, GDPR, enterprise compliance. Not required for v1, but the architecture (SecretResolutionContext, resolver, providers) already supports it.
- **Plugin secret capability declaration** — Plugins (e.g. OpenAI, Postgres) can optionally declare **required secrets** (e.g. `Set<String> requiredSecrets()` → `["api_key"]`). Validation at **pipeline deploy time** can then fail with a clear error (e.g. "Missing secret openai.api_key") instead of at runtime. Prevents runtime failure and improves DX.

---

## 19. Failure Modes

Define behavior for production debugging and secure failure:

| Scenario | Behavior |
|----------|----------|
| Secret not found | **Fail fast** — do not proceed; clear error (e.g. `SecretNotFoundException`) |
| Vault unavailable | **Retry** (with backoff); then fail with `SecretProviderUnavailableException` |
| Provider missing | **Configuration error** — registry references a provider that is not registered |
| Access denied | **Fail secure** — do not return partial data; fail with access-denied error |

Explicit exceptions (e.g. `SecretNotFoundException`, `SecretProviderUnavailableException`) help debugging and monitoring.

---

## 20. Security Model

- **Secrets never enter Temporal history** — resolution happens only in activities (worker), not in workflow code; workflow code must never receive or log raw secrets.
- **Secrets only resolved in activities** — at runtime when building plugin config or executing steps.
- **Secrets masked in logs** — value-based masking (see §11) so no resolved secret string is written to logs, UI, or execution events.
- **Secrets not stored in pipeline configs** — only references (`${secret:...}`) are stored; actual values exist only in vault and in memory during resolution.
- **Secret leak prevention in exceptions** — Resolved secret values must **never** be embedded in exception messages or stack traces. Example bad pattern: `throw new RuntimeException("Failed with key " + apiKey)`. Use a **SecretSafeExceptionFormatter** (or equivalent): when serializing or logging exceptions, mask any known secret value (e.g. from the same resolved-secrets set used for log masking). Guideline: never embed resolved secrets in exception messages; mask during exception serialization.
- **Secret size limits (optional hardening)** — Secrets can accidentally be huge (certificate bundles, JSON documents, binary data). Add optional config limits to protect workers from memory abuse, e.g. `secret.max_size = 32kb`, `secret.max_fields = 50`. Reject or truncate (per policy) when a resolved secret exceeds these limits.

---

## 21. Performance Considerations

- **Caching** — Secret resolution is cached with key `(provider, path, key, tenant)` and TTL (see §9). Use **Caffeine** for a thread-safe, concurrent cache. This avoids hitting the vault on every run and supports high QPS. TTL enables rotation without restart.
- **Prefetch** — Collect references, then prefetch all distinct logical ids (see §6c) before interpolating; reduces vault latency spikes.
- **Batch resolution** — Resolve each logical id once; group by logical id so subfields (e.g. `db.prod.username`, `db.prod.password`) share one vault read (see §6b).
- **Descriptor normalization** — Normalize path (e.g. substitute `{tenant}`) before cache lookup so cache keys are stable and duplication bugs are avoided (see §9).
- **Structured secrets** — Resolving `${secret:db.prod}` once and then serving `db.prod.username` and `db.prod.password` from the same cached `SecretValue` reduces vault calls.

---

## 22. Metrics for Observability

Production systems should track secret resolution and cache behavior. Recommended metrics:

| Metric | Purpose |
|--------|---------|
| `secret.resolve.success` | Count successful resolutions (per provider or logical id optional). |
| `secret.resolve.failure` | Count failures (vault error, not found, access denied). |
| `secret.cache.hit` | Cache returned a value without calling the provider. |
| `secret.cache.miss` | Resolve required a provider call (and optionally cache put). |
| `secret.provider.latency` | Latency per provider call (e.g. histogram or p99). |

**Why:** These help detect **vault outages** (spike in failure or latency), **cache misconfigurations** (e.g. TTL too low → high miss rate), and **secret usage spikes** (high resolve or hit rate). Extremely useful for production debugging and SLOs. Emit from SecretResolver and cache layer (e.g. Caffeine has built-in stats).

---

## 23. Summary Diagram

```
Pipeline config: api_key: ${secret:openai.api_key}
                        ↓
ConfigInterpolator (recursive walk)
                        ↓
SecretResolver  →  SecretParser  →  SecretReference  →  Secret Registry  →  SecretDescriptor
                        ↓
                 SecretProvider (plugin)  →  SecretValue (single or multi-field)
                        ↓
                 Vault (Vault / AWS / Env / …)
                        ↓
                 Resolved config: api_key: "sk-xxxxx"  (+ track value for masking)
                        ↓
                 Plugin (never sees reference or vault)
```

This keeps Olo **vault-agnostic**, **pluggable**, and **secure by design**, and allows it to work with any credential vault interchangeably.
