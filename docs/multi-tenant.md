# Multi-tenant separation

All data and configuration are scoped by **tenant id**. The tenant id comes from the workflow input: `context.tenantId`. If missing or blank, it is normalized using **OLO_DEFAULT_TENANT_ID** (environment variable); if that is unset, a fixed default UUID is used (see `OloConfig.normalizeTenantId(String)`).

## Redis keys

Session, pipeline config, and quota keys use the **tenant-first** pattern `<tenantId>:olo:...`. Default prefixes (from `OloConfig`) are `<tenant>:olo:kernel:config` and `<tenant>:olo:kernel:sessions:`; the placeholder is replaced with the actual tenant id at runtime. If you set the legacy env value `OLO_SESSION_DATA=olo:kernel:sessions:` or `OLO_CONFIG_KEY_PREFIX=olo:kernel:config`, the worker still emits **tenant-first** keys (e.g. `2a2a91fb-...:olo:kernel:sessions:wf-001:USERINPUT`).

| Use | Key pattern | Example |
|-----|-------------|---------|
| Session (workflow input) | `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT` | `acme:olo:kernel:sessions:tx-1:USERINPUT` |
| Pipeline config | `<tenantId>:olo:kernel:config:<queueName>:<version>` | `acme:olo:kernel:config:olo-chat-queue-oolama:1.0` |
| **Active workflow count** (quota) | `<tenantId>:olo:quota:activeWorkflows` | `acme:olo:quota:activeWorkflows` (Redis INCR on run start, DECR on run end) |
| Input cache (CACHE storage) | `olo:<tenantId>:worker:<transactionId>:input:<inputName>` | `olo:acme:worker:tx-1:input:userQuery` |
| **Tenant list** (bootstrap) | `olo:tenants` | JSON array of `{"id":"...","name":"...","config":{...}}`; if present, used instead of OLO_TENANT_IDS; optional `config` is tenant-specific (plugins, features, restrictions) |

Session and config prefixes are built from `OloConfig.getSessionDataPrefix(tenantId)` and `OloConfig.getPipelineConfigKeyPrefix(tenantId)`. The worker **INCR**s `<tenantId>:olo:quota:activeWorkflows` when starting a workflow run and **DECR**s it in a `finally` when the run ends (success or failure), so you can enforce or monitor per-tenant concurrency.

## Global configuration

Pipeline configuration is stored in **GlobalConfigurationContext** (runtime configuration store) as **Map&lt;tenantKey, Map&lt;queueName, GlobalContext&gt;&gt;** (GlobalContext here is the per-queue entry type from olo-worker-execution-tree, not the bootstrap wrapper):

- **Bootstrap**: The list of tenants to load is resolved from Redis key **`olo:tenants`** (JSON array of `{"id":"<tenantId>","name":"<display name>"}`). If that key is missing or invalid, **OLO_TENANT_IDS** from env is used (default `["default"]`). For each tenant in that list, the loader loads config for all task queues and stores under that tenant in **GlobalConfigurationContext** (runtime store). Bootstrap returns a **BootstrapContext** wrapper (OloConfig + flattened config map for validation), not the store itself.
- **Runtime**: When running a workflow, `LocalContext.forQueue(tenantId, queueName)` looks up the config for that tenant and queue from **GlobalConfigurationContext**. The tenant id is taken from `workflowInput.getContext().getTenantId()` and normalized with `OloConfig.normalizeTenantId(String)`.
- **Fallback**: If no config is loaded for the request tenant (e.g. only `default` is in `OLO_TENANT_IDS` but the workflow sends another `context.tenantId`), the worker falls back to the **default** tenant’s config so single-tenant or shared-config setups work. Session and cache keys still use the request tenant id.

## Database

DB tables that store pipeline config (or any tenant-scoped data) should include a **tenant_id** column. The interfaces `ConfigSource.getFromDb(tenantId, queueName, version)` and `ConfigSink.putInDb(tenantId, queueName, version, json)` accept tenant id; implementations can use it in queries and inserts.

## Configuration

- **OLO_TENANT_IDS**: Comma-separated list of tenant ids to load at bootstrap when Redis `olo:tenants` is not set (default: `default`). Each tenant gets config loaded for all task queues.
- **OLO_DEFAULT_TENANT_ID**: Tenant id used when workflow `context.tenantId` is missing or blank (normalization). If not set, a fixed default UUID is used. Set to your primary tenant (e.g. a UUID) so blank context uses that tenant’s config and keys.
- **Tenant from workflow**: Each workflow input can set `context.tenantId`. If not set, **OLO_DEFAULT_TENANT_ID** (or the fixed default UUID) is used.
- **Redis `olo:tenants`**: Optional. JSON array of `{"id":"<tenantId>","name":"<display name>","config":{...}}`. If present and valid at bootstrap, this list is used instead of OLO_TENANT_IDS. The optional **`config`** object is tenant-specific configuration (plugins, features, 3rd party deps, restrictions). See **Tenant-specific configuration** below.

## Tenant-specific configuration

Each tenant can have a **config** object in `olo:tenants`. At bootstrap, configs are stored in **TenantConfigRegistry** and passed at runtime to:

- **Plugins**: `ModelExecutorPlugin.execute(inputs, TenantConfig)` receives tenant config. Use for 3rd party URLs, API keys, or restrictions (e.g. `ollamaBaseUrl`, `ollamaModel` override for Ollama plugin).
- **Features**: `NodeExecutionContext.getTenantId()` and `getTenantConfigMap()` expose tenant id and the same config map for tenant-specific restrictions or behavior in pre/post hooks.
- **Quota**: `config.quota.softLimit` and `config.quota.hardLimit` (numbers) are used by **QuotaFeature** (PRE phase): if current usage (from Redis `<tenantId>:olo:quota:activeWorkflows`) exceeds soft limit (with optional 5% burst) or hard limit, the feature throws **QuotaExceededException** (fail-fast). Add `"quota"` to pipeline scope.features to enable. INCR at run start and DECR in `finally` ensure the counter does not drift.
- **Metrics**: `config.metrics.includeModelTag` (boolean, default false) controls whether **MetricsFeature** adds a `modelId` tag to plugin metrics. Set to `true` only when the model set is small and fixed; dynamic model names can explode Prometheus cardinality.

Example `olo:tenants` with config:

```json
[
  {"id":"2a2a91fb-f5b4-4cf0-b917-524d242b2e3d","name":"Acme","config":{"ollamaBaseUrl":"http://ollama.acme:11434","ollamaModel":"llama3.2","restrictions":["no-export"],"quota":{"softLimit":100,"hardLimit":120}}},
  {"id":"tenant-b","name":"Tenant B","config":{}}
]
```

If `olo:tenants` is missing or entries have no `config`, **TenantConfigRegistry.get(tenantId)** returns **TenantConfig.EMPTY** (empty map).

## Versioned config

When updating config (e.g. hot reload or admin API), use `VersionedConfigSupport.validateAndPut(tenantKey, queueName, config)` so the config is validated and stored under the correct tenant and queue.
