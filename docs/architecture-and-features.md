# OLO Worker: Architecture and Features

This document describes the architecture of the OLO Temporal worker and all features in their current implementation. It is updated to match the codebase: multi-tenant (tenant-scoped Redis and config), **tenant list and tenant-specific config** from Redis `olo:tenants` (or OLO_TENANT_IDS), **tenant-scoped PluginRegistry**, **TenantConfig** / **TenantConfigRegistry** for plugins and features, **unknown-tenant check** at workflow start, **immutable config snapshots** (ExecutionConfigSnapshot per run), **execution version pinning** (optional `routing.configVersion`), **QuotaFeature** (PRE phase, fail-fast from tenant config soft/hard limits), Redis INCR/DECR activeWorkflows for monitoring; feature phases (pre / postSuccess / postError / finally), VariableEngine sentinel for nulls, LocalContext and config keyed by tenant and queue, and version validation at bootstrap and on config change. **olo-worker-protocol** holds contracts (BootstrapContext, WorkerBootstrapContext, BootstrapContributor, PluginExecutor, PluginExecutorFactory) so plugins/tools/features depend on the contract only; the worker calls **OloBootstrap.initializeWorker()** and uses **WorkerBootstrapContext** (runResourceCleanup on shutdown). Linear execution trees schedule one Temporal activity per leaf via **ExecuteNodeDynamicActivity**.

---

## 1. High-level architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              OLO Worker (JVM)                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Bootstrap (olo-worker-bootstrap)                                                 │
│    • OloConfig from env (OLO_QUEUE, OLO_TENANT_IDS, OLO_DEFAULT_TENANT_ID,       │
│      OLO_CACHE_*, OLO_DB_*, OLO_RUN_LEDGER, OLO_SESSION_DATA, OLO_CONFIG_*, …)   │
│    • Tenant list from Redis olo:tenants (JSON array id/name/config); if missing,  │
│      use OLO_TENANT_IDS and write list back to olo:tenants                        │
│    • TenantConfigRegistry: parse config from olo:tenants entries → per-tenant     │
│      config (plugins, features, 3rd party deps, restrictions)                  │
│    • For each tenant, load pipeline config per queue → GlobalConfigurationContext │
│      (tenant-scoped Redis keys: <tenantId>:olo:kernel:config:...); return BootstrapContext │
│    • initializeWorker(): register plugins/tools, run BootstrapContributors, build WorkerBootstrapContext (runLedger, sessionCache, PluginExecutorFactory, runResourceCleanup) │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Global state                                                                     │
│    • GlobalConfigurationContext: Map<tenantKey, Map<queueName, GlobalContext>>    │
│      (read-only; config keyed by tenant then queue)                               │
│    • TenantConfigRegistry: tenantId → TenantConfig (from olo:tenants config)       │
│    • FeatureRegistry: feature name → FeatureEntry (PreNodeCall, FinallyCall, etc.; │
│      phase routing: isPre, isPostSuccess, isPostError, isFinally)                 │
│    • PluginRegistry: Map<tenantId, Map<pluginId, PluginEntry>> (tenant-scoped)   │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Temporal Worker                                                                  │
│    • One Worker per task queue (OLO_QUEUE + optional -debug queues)               │
│    • Workflows: OloKernelWorkflowImpl                                             │
│    • Activities: OloKernelActivitiesImpl, ExecuteNodeDynamicActivity (processInput, executePlugin, getChatResponse, getExecutionPlan, executeNode, applyResultMapping, runExecutionTree; optional RunLedger) │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Execution flow (per workflow run)                                                │
│    1. processInput(workflowInputJson) → cache WorkflowInput at tenant-scoped     │
│       session key <tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT       │
│    2. getExecutionPlan; if linear → one activity per leaf (executeNode); else runExecutionTree(queueName, workflowInputJson) │
│       • tenantId = normalize(workflowInput.context.tenantId)                    │
│       • If tenantId not in allowed list → throw IllegalArgumentException         │
│       • INCR <tenantId>:olo:quota:activeWorkflows; try { … } finally { DECR } (DECR always runs)  │
│       • Resolve effective queue (input or activity task queue for -debug)         │
│       • requestedVersion = routing.configVersion (optional execution version pinning) │
│       • LocalContext.forQueue(tenantId, effectiveQueue, requestedVersion) → deep copy; version check │
│       • ExecutionConfigSnapshot.of(tenantId, queue, config, snapshotVersionId)   │
│       • tenantConfigMap = TenantConfigRegistry.get(tenantId).getConfigMap()        │
│       • ExecutionEngine.run(snapshot, inputValues, pluginExecutor, tenantConfigMap) │
│       • Per node: NodeExecutionContext has tenantId + tenantConfigMap; runPre →   │
│         execute → postSuccess/postError → finally; PluginInvoker uses tenantId;   │
│         plugin.execute(inputs, TenantConfig)                                     │
│    3. Return workflow result string                                              │
└─────────────────────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   Redis (tenant-scoped)  Pipeline config       Ollama (or other
   <tenantId>:olo:...     (Redis/DB/file)      model-executor plugin)
```

### 1.1 Trust boundaries

For security and audit, the stack is split into clear trust boundaries:

```
    ┌─────────────────────────────────────┐
    │  Workflow layer                     │  ← Deterministic; no I/O
    │  (OloKernelWorkflowImpl)            │     Only activity stubs + input/return values
    └─────────────────────────────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  Activity layer                     │  ← I/O allowed (Redis, DB, HTTP, config)
    │  (OloKernelActivitiesImpl)          │     Tenant check, INCR/DECR, ExecutionEngine.run
    └─────────────────────────────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  Plugin boundary                    │  ← Restricted classloader (community plugins)
    │  (PluginRegistry, ExecutablePlugin) │     No access to worker internals; tenant-scoped
    └─────────────────────────────────────┘
```

- **Workflow layer:** Must remain deterministic; no I/O, no randomness, no direct plugin or feature calls. Only invokes activity stubs.
- **Activity layer:** All I/O and execution happen here; tenant validation, config resolution, feature hooks, plugin invocation.
- **Plugin boundary:** Community plugins run in an isolated classloader; they cannot override or replace internal plugins.

---

## 2. Module map

| Module | Purpose |
|--------|---------|
| **olo-worker-protocol** | Contracts only (no impl): **BootstrapContext** (getConfig, getTaskQueues, getTenantIds, getPipelineConfigByQueue, getTemporalTargetOrDefault, getTemporalNamespaceOrDefault, **putContributorData** / **getContributorData**); **WorkerBootstrapContext** extends BootstrapContext (getRunLedger, getSessionCache, **getPluginExecutorFactory**, **runResourceCleanup**); **BootstrapContributor** (`contribute(BootstrapContext)`); **PluginExecutor** (execute(pluginId, inputsJson, nodeId), toJson/fromJson); **PluginExecutorFactory** (create(tenantId, nodeInstanceCache)). Lets plugins, tools, and features depend on the contract without pulling in bootstrap or plugin implementation. |
| **olo-annotations** | Declarative metadata: `@OloFeature` (name, phase, applicableNodeTypes, contractVersion), `@OloPlugin`, `@OloUiComponent`; DTOs (FeatureInfo, PluginInfo, UiComponentInfo with contractVersion); **ResourceCleanup** (`onExit()` for plugins/features at worker shutdown); annotation processor generating `META-INF/olo-features.json`, `olo-plugins.json`, `olo-ui-components.json` for bootstrap/UI. |
| **olo-worker-configuration** | `OloConfig` from environment: task queues, **OLO_TENANT_IDS**, **OLO_DEFAULT_TENANT_ID**, cache, DB, session prefix, pipeline config dir/version/retry/key prefix; **normalizeTenantId(String)**; **getSessionDataPrefix(tenantId)**, **getPipelineConfigKeyPrefix(tenantId)**; **TenantConfig** / **TenantConfigRegistry** (incl. **quota.softLimit** / **quota.hardLimit** per tenant); **TenantEntry**; `OloSessionCache` (incr/decr/getActiveWorkflowsCount for `<tenantId>:olo:quota:activeWorkflows`); Redis pipeline config source/sink. |
| **olo-worker-input** | `WorkflowInput`, `InputItem`, **`Routing`** (pipeline, transactionType, transactionId, **configVersion** optional for execution version pinning), `Context`, `Metadata`; storage modes (LOCAL, CACHE, FILE); **InputStorageKeys.cacheKey(tenantId, transactionId, inputName)** for CACHE storage (`olo:<tenantId>:worker:...`). |
| **olo-worker-execution-tree** | Pipeline model: `PipelineConfiguration`, `PipelineDefinition`, `ExecutionTreeNode` (preExecution, postExecution, **postSuccessExecution**, **postErrorExecution**, **finallyExecution**, featureRequired, featureNotRequired), `Scope` (PluginDef, FeatureDef with optional **contractVersion**); `ExecutionTreeConfig`; **ConfigurationLoader.loadConfiguration(tenantKey, queueName, version)** (Redis key tenant-scoped; DB get/put take tenantId); **GlobalConfigurationContext**: Map&lt;tenantKey, Map&lt;queueName, GlobalContext&gt;&gt;; get(tenantKey, queueName), put(tenantKey, queueName, config), loadAllQueuesAndPopulateContext(tenantKey, queueNames, ...). |
| **olo-worker-execution-context** | **LocalContext.forQueue(tenantKey, queueName)** and **forQueue(tenantKey, queueName, configVersion)** (version check: throws **ConfigVersionMismatchException** if requested version != loaded); deep copy of pipeline config from global context. **ExecutionConfigSnapshot** (immutable snapshot: tenantId, queueName, pipelineConfiguration, snapshotVersionId, optional **runId** for ledger); used by ExecutionEngine so no global config reads during run. |
| **olo-worker-features** | Phase contracts: **PreNodeCall** (PRE), **PostSuccessCall** (POST_SUCCESS), **PostErrorCall** (POST_ERROR), **FinallyCall** (FINALLY), **PreFinallyCall** (PRE_FINALLY); **FeatureRegistry** (register by name/phase/applicableNodeTypes/contractVersion/**privilege**; **FeatureEntry**: isPre, isPostSuccess, isPostError, isFinally, **isInternal/isCommunity** for phase and privilege routing); **FeaturePrivilege** (INTERNAL, COMMUNITY); **ObserverPreNodeCall** / **ObserverPostNodeCall** (observer-only contracts); **FeatureAttachmentResolver** (resolve pre, postSuccess, postError, finally lists from node + queue + scope + registry); **NodeExecutionContext** (nodeId, type, nodeType, attributes, **getTenantId()**, **getTenantConfigMap()**); **ResolvedPrePost** (getPreExecution, getPostSuccessExecution, getPostErrorExecution, getFinallyExecution; legacy getPostExecution returns union). |
| **olo-internal-features** | Aggregates kernel-privileged features: **InternalFeatures.registerInternalFeatures(registry, sessionCache, runLedgerOrNull)** registers DebuggerFeature, QuotaFeature, MetricsFeature, and (if ledger enabled) RunLevelLedgerFeature, NodeLedgerFeature; **InternalFeatures.clearLedgerForRun()** for run cleanup. Worker depends on this module and calls it at startup; no direct dependency on olo-feature-debug, olo-feature-quota, olo-feature-metrics from olo-worker. |
| **olo-feature-debug** | `DebuggerFeature`: PreNodeCall + FinallyCall; `@OloFeature(name = "debug", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`. before/afterFinally log nodeId, type, nodeType, result presence. Registered at startup; auto-attached when queue ends with `-debug`. |
| **olo-feature-quota** | **QuotaFeature**: `@OloFeature(name = "quota", phase = PRE, applicableNodeTypes = {"SEQUENCE"})`. **Must only run on the root node and only once per run**—enable via pipeline scope.features only; do not attach per node (e.g. node-level preExecution/features). Runs once at pipeline root (first SEQUENCE), before any plugin execution; reads **OloSessionCache.getActiveWorkflowsCount(tenantId)**; compares with **tenantConfig.quota.softLimit** / **quota.hardLimit**; if usage &gt; limit throws **QuotaExceededException** (fail-fast, no blocking). **QuotaContext** holds session cache (set at worker startup). Optional 5% burst over softLimit. |
| **olo-feature-metrics** | **MetricsFeature**: `@OloFeature(name = "metrics", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`. Lazy self-bootstrapping: static **AtomicReference&lt;MeterRegistry&gt;**; on first execution creates **SimpleMeterRegistry** via CAS and reuses forever (thread-safe, lock-free). In **afterFinally()** increments **olo.node.executions** counter with tags **tenant**, **nodeType**. Implements **ResourceCleanup**. Kernel untouched. |
| **olo-run-ledger** | **Run ledger** (env-gated by **OLO_RUN_LEDGER**). **Write-only, fail-safe, append-only.** Each node **attempt** is recorded separately; uniqueness **(run_id, node_id, attempt)**. **LedgerStore**, **NoOpLedgerStore**, **JdbcLedgerStore** (PostgreSQL: **olo_run**, **olo_run_node**, **olo_config**; **UUID** for run_id, tenant_id, node_id, parent_node_id; **TIMESTAMPTZ** for timestamps); **RunLedger**; **LedgerContext** (runId set in NodeExecutor from ExecutionConfigSnapshot, including ASYNC path); **NodeAiMetrics**, **NodeReplayMeta**, **NodeFailureMeta**. **RunLevelLedgerFeature** (root: run start/end, config snapshot in olo_config); **NodeLedgerFeature** (every node: scope includes ledger-node when registered). Run end: error_message, failure_stage, total_prompt_tokens, total_completion_tokens, currency; node end: error_code, error_message, error_details (JSONB), prompt_cost, completion_cost, total_cost, temperature, top_p, provider_request_id, attempt, max_attempts, backoff_ms, parent_node_id, execution_order, depth. See docs/run-ledger-schema.md. |
| **olo-worker-plugin** | `ContractType`, **ModelExecutorPlugin** (`execute(inputs)` default, **execute(inputs, TenantConfig)** for tenant-specific params); **ReducerPlugin** (REDUCER for JOIN merge); **PluginRegistry** tenant-scoped: **Map&lt;tenantId, Map&lt;pluginId, PluginEntry&gt;&gt;**; **get(tenantId, pluginId)**, **getModelExecutor(tenantId, pluginId)**, **getReducer(tenantId, pluginId)**; getContractVersion(tenantId, pluginId); getAllByTenant() for shutdown. **PluginExecutorFactory** implementation (wires PluginRegistry; bootstrap puts it in **WorkerBootstrapContext**); **PluginManager**, **PluginProvider** for discovery and registration. Worker uses protocol **PluginExecutor** / **PluginExecutorFactory** only. |
| **olo-join-reducer** | **OutputReducerPlugin** (ReducerPlugin): clubs labeled inputs into `combinedOutput` string (one line per label). **OutputReducerPluginProvider** (OUTPUT_REDUCER, REDUCER). Used by **JOIN** nodes with **mergeStrategy: REDUCE** and pluginRef OUTPUT_REDUCER; registered via olo-internal-plugins. |
| **olo-plugin-ollama** | `OllamaModelExecutorPlugin` (MODEL_EXECUTOR): **execute(inputs, TenantConfig)** uses tenantConfig.get("ollamaBaseUrl"), get("ollamaModel") when set; `/api/chat`; `@OloPlugin`; registered per tenant as e.g. `GPT4_EXECUTOR` (env or tenant config overrides). |
| **olo-worker-bootstrap** | **OloBootstrap.initialize()**: build `OloConfig`; resolve tenant list from Redis **olo:tenants** (or OLO_TENANT_IDS); for each tenant load pipeline config into **GlobalConfigurationContext**; return **BootstrapContext** (config, getTaskQueues, getTenantIds, getPipelineConfigByQueue, putContributorData/getContributorData). **OloBootstrap.initializeWorker()**: call initialize(); create **PluginManager**, register internal plugins/tools; **run BootstrapContributor**s (e.g. planner) with context; create sessionCache and runLedger (if enabled); register features; validate pipeline configs; return **WorkerBootstrapContext** (adds getRunLedger, getSessionCache, **getPluginExecutorFactory**, **runResourceCleanup**). Implementations: **BootstrapContextImpl**, **WorkerBootstrapContextImpl**. |
| **olo-worker** | `OloWorkerApplication`: calls **OloBootstrap.initializeWorker()** (no bootstrap logic in worker); uses **WorkerBootstrapContext** (config, queues, tenants, runLedger, sessionCache, **pluginExecutorFactory**, **runResourceCleanup**). **OloKernelActivitiesImpl**(sessionCache, allowedTenantIds, runLedger, **pluginExecutorFactory**); **ExecuteNodeDynamicActivity** (handles per-node activity when tree is linear; activity type "NODETYPE" or "PLUGIN:pluginRef"). Workflow: **getExecutionPlan**; if linear → one activity per leaf via executeNode; else **runExecutionTree**. INCR activeWorkflows; try { LocalContext, snapshot, **ExecutionEngine.run(snapshot, pluginExecutor, ...)** } finally { DECR }; NodeExecutor, PluginInvoker (uses protocol **PluginExecutor**), ResultMapper. Shutdown: **ctx.runResourceCleanup()**. |

---

## 3. Features in detail

### Feature list (summary)

- **Multi-tenant**: Tenant-scoped Redis/config; tenant list from Redis `olo:tenants` or `OLO_TENANT_IDS`; unknown-tenant check at workflow start.
- **Tenant-specific config**: `TenantConfig` / `TenantConfigRegistry` from `olo:tenants`; plugins and features receive tenant config (e.g. API URLs, restrictions).
- **Immutable config snapshots**: Each run uses **ExecutionConfigSnapshot** (tenantId, queueName, pipeline config deep copy, snapshotVersionId); no global config reads during execution.
- **Execution version pinning**: Optional **routing.configVersion**; **LocalContext.forQueue(tenantKey, queueName, configVersion)** validates version. **Resolution policy:** if a config version is requested and the loaded config's version does not match, **LocalContext.forQueue** throws **ConfigVersionMismatchException** and the run fails. There is no multi-version store—only the config loaded at bootstrap is used; no fallback to an older or newer version.
- **Quota (fail-fast)**: **QuotaFeature** (PRE phase, applicable to SEQUENCE). **Must only run on the root node and only once per run**—enable via scope.features only; do not attach per node. Runs before any plugin execution; reads current usage from Redis **getActiveWorkflowsCount(tenantId)**; compares with **tenantConfig.quota.softLimit** / **quota.hardLimit**; if exceeded throws **QuotaExceededException** (no blocking). Redis **INCR** at run start, **DECR** in **finally** (always runs so quota does not drift).
- **Plugin/feature contracts**: Tenant-scoped **PluginRegistry**; **ModelExecutorPlugin.execute(inputs, TenantConfig)**; **ResourceCleanup.onExit()** at shutdown.
- **Session storage**: Tenant-first keys `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`.
- **Run ledger** (optional): When **OLO_RUN_LEDGER=true**, RunLevelLedgerFeature (root) and NodeLedgerFeature (every node) are registered. **Ledger is write-only and append-only.** Each node **attempt** is recorded separately; uniqueness is **(run_id, node_id, attempt)**. No in-place update of a previous attempt—each retry gets its own row when the schema includes attempt in the key. Activity records run start/end with **duration_ms**, run-level **error_message**, **failure_stage**, **total_prompt_tokens**, **total_completion_tokens**, **currency**; run end aggregates **total_nodes**, **total_cost**, **total_tokens**. Config snapshot (config_version, snapshot_version_id, plugin_versions) is stored only in **olo_config** (immutable per run). For **MODEL** and **PLANNER** node types, NodeLedgerFeature persists **token_input_count**, **token_output_count**, **model_name**, **provider**, **prompt_cost**, **completion_cost**, **total_cost**; replay (**prompt_hash**, **model_config_json**, **tool_calls_json**, **temperature**, **top_p**, **provider_request_id**) and failure meta (**error_code**, **error_message**, **error_details**, **retry_count**, **attempt**, **max_attempts**, **backoff_ms**, **execution_stage**, **failure_type**). See docs/run-ledger-schema.md. hierarchy (**parent_node_id**, **execution_order**, **depth**). **run_id**, **tenant_id**, **node_id**, **parent_node_id** are **UUID**; timestamps **TIMESTAMPTZ**. Write-only, fail-safe. See docs/run-ledger-schema.md.
- **Feature phases**: Pre, postSuccess, postError, finally; **NodeExecutionContext** with tenantId and tenantConfigMap.

### 3.1 Annotations and generated metadata (olo-annotations)

- **@OloFeature**  
  Marks a class as a feature. Attributes: `name`, **`contractVersion`** (default `"1.0"`, for config compatibility), `phase` (PRE, POST_SUCCESS, POST_ERROR, FINALLY, PRE_FINALLY), `applicableNodeTypes` (e.g. `"*"`, `"MODAL.*"`). Used by the feature registry and by the annotation processor to generate feature metadata.

- **@OloPlugin**  
  Marks a class as an OLO plugin. Attributes: `id`, `displayName`, `contractType`, `description`, `category`, `icon`, `inputParameters`, `outputParameters` (each `@OloPluginParam`). Used for drag-and-drop and variable mapping metadata; aligns with execution tree scope and `PluginRegistry`.

- **@OloUiComponent**  
  Marks a class as a plug-and-play UI component. Attributes: `id`, `name`, `category`, `description`, `icon`. Used to generate UI component discovery JSON.

- **Annotation processor**  
  Scans for `@OloFeature`, `@OloPlugin`, `@OloUiComponent` and writes:
  - `META-INF/olo-features.json` (FeatureInfo: name, **contractVersion**, phase, applicableNodeTypes, className)
  - `META-INF/olo-plugins.json` (PluginInfo: id, displayName, contractType, **contractVersion**, params, className)
  - `META-INF/olo-ui-components.json` (UiComponentInfo: id, name, category, className)

These files support loading features/plugins/UI components at bootstrap or in a UI builder.

---

### 3.2 Feature framework (olo-worker-features)

- **Registered features list**  
  Features registered at worker startup and available to pipeline scope:

  | Feature | Phase | Module | Description |
  |---------|-------|--------|-------------|
  | **debug** | PRE_FINALLY | olo-feature-debug | Pre/post logging (nodeId, type, nodeType, result presence). Auto-attached when queue name ends with `-debug`. Contract version: 1.0. |

  Pipeline `scope.features` can reference these by id (e.g. `{"id":"debug","displayName":"Debug","contractVersion":"1.0"}`). When the task queue ends with `-debug`, the resolver adds `debug` to the effective feature list for applicable nodes.

- **FeaturePhase** (olo-annotations)  
  When a feature is invoked relative to node execution:
  - **PRE** — before the node executes.
  - **POST_SUCCESS** — after the node completes without throwing.
  - **POST_ERROR** — after the node throws an exception.
  - **FINALLY** — after the node completes (success or error).
  - **PRE_FINALLY** — before the node and again after (success or error).

  The executor runs **one pre list** before the node and **three post lists** after: **postSuccess** (on normal completion), **postError** (on exception), **finally** (always). Features are routed by phase: PRE/PRE_FINALLY → pre; POST_SUCCESS/PRE_FINALLY → postSuccess; POST_ERROR/PRE_FINALLY → postError; FINALLY/PRE_FINALLY → finally.

- **Phase contracts (five; implement one or more per feature):**
  - **PreNodeCall** — `void before(NodeExecutionContext context)`. Phase PRE (and pre part of PRE_FINALLY).
  - **PostSuccessCall** — `void afterSuccess(NodeExecutionContext context, Object nodeResult)`. Phase POST_SUCCESS.
  - **PostErrorCall** — `void afterError(NodeExecutionContext context, Object nodeResult)`. Phase POST_ERROR.
  - **FinallyCall** — `void afterFinally(NodeExecutionContext context, Object nodeResult)`. Phase FINALLY.
  - **PreFinallyCall** — extends PreNodeCall and adds `afterSuccess`, `afterError`, `afterFinally`. Phase PRE_FINALLY (before + all three post moments).

  **When to use which:** Use **POST_SUCCESS** / **POST_ERROR** (PostSuccessCall, PostErrorCall, or PreFinallyCall afterSuccess/afterError) for **heavy lifting** — logic that may throw or needs success-vs-error handling. Use **FINALLY** / **PRE_FINALLY** (FinallyCall or PreFinallyCall afterFinally) for **non–exception-prone** code (logging, metrics, cleanup) to achieve the functionality.

- **FeatureRegistry**  
  Singleton. Register feature instances (with `@OloFeature` or explicit metadata: name, phase, applicableNodeTypes, optional contractVersion, **privilege**). Use **registerInternal(instance)** or **registerCommunity(instance)** to set privilege; **register(instance)** defaults to INTERNAL. Look up by name; **getContractVersion(name)** for config compatibility. **FeatureEntry** exposes **isPre()**, **isPostSuccess()**, **isPostError()**, **isFinally()**, **getPrivilege()** / **isInternal()** / **isCommunity()** so the resolver and executor can route and enforce behavior. Default phase when not specified: **PRE_FINALLY**. Features that hold resources should implement **ResourceCleanup**; the worker calls **onExit()** on each at shutdown.

- **FeatureAttachmentResolver**  
  Resolves the effective pre and post feature name lists for a node by merging:
  - Node’s `preExecution`, `postSuccessExecution`, `postErrorExecution`, `finallyExecution` (and legacy `postExecution` into all three post lists)
  - Node’s `features` (routed by each feature’s phase to pre / postSuccess / postError / finally)
  - Pipeline/scope enabled features (when queue name ends with `-debug`, `"debug"` is added)
  - Node’s `featureRequired`
  - Excluding node’s `featureNotRequired`

- **NodeExecutionContext**  
  Immutable context passed to pre/post: `nodeId`, `type`, `nodeType`, optional `attributes`, **getTenantId()**, **getTenantConfigMap()** (tenant-specific config for restrictions or 3rd party params). Used by features for tenant-aware behavior.

- **ResolvedPrePost**  
  Result of resolution: **getPreExecution()**, **getPostSuccessExecution()**, **getPostErrorExecution()**, **getFinallyExecution()** — ordered lists of feature names (no duplicates). Legacy **getPostExecution()** returns the union of the three post lists. The executor runs **pre** → execute → **postSuccess** (on normal completion) or **postError** (on exception) → **finally** (always).

#### 3.2.1 Feature execution order (defined contract)

A single-page diagram reference: [**feature-ordering-diagram.md**](feature-ordering-diagram.md).

**Phase execution flow (per node):**

```
    ┌─────────────────────────────────────┐
    │  PRE                                │  ← All features in resolved pre list (in order)
    └─────────────────────────────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  NODE EXECUTION                     │  ← Plugin invoke, sequence no-op, etc.
    └─────────────────────────────────────┘
                      ↓
         ┌────────────┴────────────┐
         ↓                         ↓
    ┌─────────────┐          ┌─────────────┐
    │ POST_SUCCESS│          │ POST_ERROR  │  ← On normal completion   ← On exception
    └─────────────┘          └─────────────┘
         └────────────┬────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  FINALLY                             │  ← Always runs (after postSuccess or postError)
    └─────────────────────────────────────┘
```

**Resolved feature order (within each phase):**  
The order of feature names in each list is determined by merge order below. **featureNotRequired** excludes; **first occurrence wins** (no duplicates). **scope.features** and **node.features** order are each preserved; if the same feature is in both, **node.features wins** for position (processed before scope).

```
    Merge order (first → last = execution order):

    1. Node explicit    preExecution, postSuccessExecution, postErrorExecution, finallyExecution
    2. Legacy           postExecution (→ all three post lists)
    3. Node shorthand   features   ← order preserved; same feature here and in scope → position from here
    4. Scope + queue    pipeline scope features, queue-based (e.g. -debug → "debug")   ← order preserved
    5. Required         featureRequired

    Excluded at any step:  featureNotRequired
```

**Phase order (per node):**  
For each execution tree node, the executor runs phases in this order:

1. **Pre** — All features in the resolved pre list, in list order.
2. **Node** — Node logic (e.g. plugin invoke, sequence no-op).
3. **PostSuccess** (if the node completed normally) — All features in the resolved postSuccess list, in list order.
4. **PostError** (if the node threw) — All features in the resolved postError list, in list order.
5. **Finally** — All features in the resolved finally list, in list order. Always runs after PostSuccess or PostError.

**Within-phase order (order of feature names in each list):**  
Determined by **FeatureAttachmentResolver**. Features are merged in this order (first occurrence wins; no duplicates):

1. Node’s explicit lists: `preExecution`, `postSuccessExecution`, `postErrorExecution`, `finallyExecution`.
2. Legacy `postExecution` (appended to all three post lists).
3. Node’s `features` (shorthand; each feature added to its phases per registry).
4. Pipeline/scope features and queue-based (e.g. queue name ends with `-debug` → add `debug`).
5. Node’s `featureRequired`.

Features in `featureNotRequired` are excluded. Execution within each phase follows the resolved list order (first name → first executed).

**Order determinism (explicit contract):**

- **Is scope.features order preserved?** Yes. Features from pipeline scope are added in the order they appear in `scope.features`; that order is preserved in the resolved list.
- **Is node.features order preserved?** Yes. Features from `node.features` are added in the order they appear in the list; that order is preserved.
- **If both specify the same feature, which wins?** The **first source in the merge order** wins for position. Merge order is: node explicit → legacy → node features → scope → required. So if the same feature appears in both `node.features` and `scope.features`, it is added when **node.features** is processed (node.features comes before scope). The feature’s position is then the one from the node.features pass; scope does not add it again (no duplicate). Do not rely on scope to define position for a feature that is also in node.features—node wins.

#### 3.2.2 Internal vs community feature privilege

Features are split into two privilege levels (similar to internal vs community plugins).

- **Internal (kernel-privileged)**  
  Registered via **registerInternal(...)** or **register(...)**. Olo-controlled, part of the fat JAR (see **olo-internal-features**). Allowed to: block execution, mutate context, affect failure semantics, persist ledger entries, enforce quotas, inject audit behavior, run in any phase. Examples: Quota, Ledger, deterministic guard, compliance, security policy. If an internal feature throws in a pre hook, the executor propagates the exception and execution fails.

- **Community (restricted)**  
  Registered via **registerCommunity(...)**. Must be **observer-class only**: may read **NodeExecutionContext**, log, emit metrics, append attributes. Must **not**: block execution, modify the execution plan, throw policy exceptions, override failure semantics, or **mutate execution state**. **NodeExecutionContext** is immutable (all fields and returned maps are read-only); community features must not mutate it or rely on mutating it. Enforcement today is by contract and immutability; the executor catches and logs community-feature exceptions but does not prevent mutation of other execution state—so implementations must respect the no-mutation contract. Optional observer contracts: **ObserverPreNodeCall**, **ObserverPostNodeCall** (same signatures as PreNodeCall/PostNodeCall; document observer-only semantics).

The executor enforces this in **NodeExecutor.runPre**: for **COMMUNITY** features it wraps the pre call in try/catch and logs on failure; for **INTERNAL** features it lets exceptions propagate. Post hooks are already catch-and-log for all features.

---

### 3.3 Debug feature (olo-feature-debug)

- **DebuggerFeature**  
  Implements `PreNodeCall` and `FinallyCall`. Annotated with `@OloFeature(name = "debug", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`.
  - **before**: logs `[DEBUG] pre  nodeId=… type=… nodeType=…` at INFO.
  - **afterFinally**: logs `[DEBUG] post nodeId=… type=… nodeType=… resultPresent=…` at INFO.

- **Registration**  
  Registered at startup via **InternalFeatures.registerInternalFeatures(...)** (olo-internal-features).

- **When it runs**  
  Debug runs as part of **tree traversal** in `runExecutionTree`: for every node the resolver attaches the debug feature when the effective queue ends with `-debug`. Because the phase is **PRE_FINALLY**, debug is added to the **pre** list and to **postSuccess**, **postError**, and **finally** lists. The executor runs pre (logs once) → execute → postSuccess or postError → finally (logs again), so debug logs appear before and after each node (including SEQUENCE and PLUGIN).

---

### 3.4 Plugin system (olo-worker-plugin, olo-plugin-ollama)

- **ContractType**  
  Constants for plugin contract types (e.g. `MODEL_EXECUTOR`, `EMBEDDING`).

- **ModelExecutorPlugin**  
  Interface: **`Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig)`** (tenant-specific config; e.g. 3rd party URL, restrictions). Default **`execute(Map inputs)`** delegates to `execute(inputs, TenantConfig.EMPTY)`. Aligns with scope `contractType: "MODEL_EXECUTOR"` and tree node `pluginRef` / inputMappings / outputMappings. Activity resolves **TenantConfig** from **TenantConfigRegistry.get(tenantId)** and passes it when invoking the plugin.

- **PluginRegistry**  
  Singleton, **tenant-scoped**: **Map&lt;tenantId, Map&lt;pluginId, PluginEntry&gt;&gt;** (`pluginsByTenant`). **registerModelExecutor(tenantId, id, plugin)**, **register(tenantId, id, contractType, ...)**. Look up: **get(tenantId, pluginId)**, **getModelExecutor(tenantId, pluginId)**, **getContractVersion(tenantId, pluginId)**. **getAllByTenant()** for iteration (e.g. shutdown). Plugins are registered per tenant at startup (same plugin instance may be registered for multiple tenants). Plugins that hold resources should implement **ResourceCleanup**; the worker calls **onExit()** on each at shutdown.

- **OllamaModelExecutorPlugin**  
  Implements `ModelExecutorPlugin.execute(inputs, tenantConfig)`. Input: `"prompt"`. Output: `"responseText"`. Uses **tenantConfig.get("ollamaBaseUrl")** and **tenantConfig.get("ollamaModel")** when present; otherwise constructor/env defaults. Calls `POST <baseUrl>/api/chat` with `model` and messages. Registered **per tenant** at startup (e.g. for each tenant in BootstrapContext.getTenantIds()).

---

### 3.5 Execution tree and pipeline configuration (olo-worker-execution-tree)

- **PipelineConfiguration**  
  Root: `version`, `executionDefaults`, `pluginRestrictions`, `featureRestrictions`, `pipelines` (map of name → PipelineDefinition).

- **ExecutionType**
  Enum: **SYNC** (default), **ASYNC**. When ASYNC, all nodes except JOIN run in a thread-pool task; JOIN runs synchronously to merge branches. Set per pipeline via `executionType` in pipeline definition. **PLANNER nodes are not allowed with ASYNC** (executor throws **IllegalStateException**); use SYNC for pipelines that contain PLANNER.

- **PipelineDefinition**  
  Per-pipeline: `name`, `workflowId`, `inputContract`, `variableRegistry`, `scope`, `executionTree`, `outputContract`, `resultMapping`, `executionType` (optional, default SYNC).

- **NodeType**  
  Enum of structural node types: SEQUENCE, IF, SWITCH, CASE, ITERATOR, FORK, JOIN, PLUGIN; Phase 2: TRY_CATCH, RETRY, SUB_PIPELINE, EVENT_WAIT; Phase 3: LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION; UNKNOWN (for unrecognized config values). JSON uses the enum name as string; `@JsonCreator` / `@JsonValue` handle round-trip.

- **ExecutionTreeNode**  
  Tree node: `id`, `displayName`, `type` (NodeType), `children`, `params` (type-specific config), `nodeType`, `pluginRef`, `inputMappings`, `outputMappings`, and feature lists: **preExecution**, **postExecution** (legacy, merged into the three below by resolver), **postSuccessExecution**, **postErrorExecution**, **finallyExecution**, **features**, **featureRequired**, **featureNotRequired**.  
  See [node-type-catalog.md](node-type-catalog.md) for purpose and params. Helpers: `withEnsuredUniqueId(node)`, `withRefreshedIds(node)`.

- **Scope**  
  **plugins** (list of PluginDef: id, displayName, contractType, **contractVersion**, inputParameters, outputParameters), **features** (list of FeatureDef: id, displayName, **contractVersion**; or strings). Deserializer supports features as strings or objects.

- **Variable execution model (3.x)**  
  See [variable-execution-model.md](variable-execution-model.md): only declared variables (variableRegistry) allowed; IN must match inputContract; INTERNAL initialized null; type validation on inputMappings/outputMappings; OUT must be assigned before completion; unknown variables rejected when inputContract.strict is true.

- **ConfigurationLoader**  
  **loadConfiguration(tenantKey, queueName, version)**: load order Redis → DB → queue file → (if -debug) base queue file → default file. Redis key uses tenant-scoped prefix (<tenantId>:olo:kernel:config:...). DB: **getFromDb(tenantId, queueName, version)** / **putInDb(tenantId, queueName, version, json)**. Normalizes config (ensure unique node IDs); can persist to Redis/DB. Used at bootstrap per tenant.

- **GlobalConfigurationContext**  
  **Runtime configuration store**: Map of tenantKey → (queueName → **GlobalContext**), where **GlobalContext** (olo-worker-execution-tree) is a single entry: queue name + PipelineConfiguration. **get(tenantKey, queueName)**, **put(tenantKey, queueName, PipelineConfiguration)**; **loadAllQueuesAndPopulateContext(tenantKey, queueNames, version, ...)** with tenant-scoped prefix. Populated by bootstrap (one load per tenant); read by activities via **LocalContext.forQueue(tenantId, queueName)**.

- **ExecutionTreeConfig**  
  JSON serialization/deserialization of `PipelineConfiguration`; `ensureUniqueNodeIds(config)`; `refreshAllNodeIds(config)`.

---

### 3.6 Bootstrap (olo-worker-bootstrap)

- **OloBootstrap.initialize()**  
  1. Build `OloConfig` from environment (includes **getTenantIds()**, **OLO_DEFAULT_TENANT_ID**).  
  2. Validate task queues (exit if empty).  
  3. **Resolve tenant list**: read Redis **olo:tenants**; if valid, use **TenantEntry.parseTenantEntriesWithConfig** and populate **TenantConfigRegistry**; else use OLO_TENANT_IDS and write list to Redis. **For each tenant** in the resolved list: compute tenant-scoped prefix `config.getPipelineConfigKeyPrefix(tenantId)`; call **GlobalConfigurationContext.loadAllQueuesAndPopulateContext(tenantId, taskQueues, version, configSourceSink, configSourceSink, configDir, retry, tenantScopedPrefix)** so config is loaded per queue with Redis keys `<tenantId>:olo:kernel:config:<queue>:<version>`; file-loaded config is written back to Redis/DB.  
  4. Build a flattened map `"tenant:queue"` → `PipelineConfiguration` and return **BootstrapContext** (interface in **olo-worker-protocol**): **getConfig()**, **getTaskQueues()**, **getTenantIds()**, **getPipelineConfigByQueue()**, **getTemporalTargetOrDefault**, **getTemporalNamespaceOrDefault**, **putContributorData(key, value)** / **getContributorData(key)** / **getContributorData()** for contributor metadata.

- **OloBootstrap.initializeWorker()**  
  Full worker bootstrap: calls **initialize()**; creates **PluginManager**, registers internal plugins and tools; runs **BootstrapContributor**s (e.g. **PlannerBootstrapContributor**) with the context so they can read pipeline config and attach data via **putContributorData**; creates sessionCache and runLedger (if enabled); registers features; validates all pipeline configs; returns **WorkerBootstrapContext** (interface in olo-worker-protocol). **WorkerBootstrapContext** extends BootstrapContext and adds **getRunLedger()**, **getSessionCache()**, **getPluginExecutorFactory()**, **runResourceCleanup()**. Implementations: **BootstrapContextImpl**, **WorkerBootstrapContextImpl** in olo-worker-bootstrap.

- **BootstrapContext** (contract)  
  Read-write view of bootstrap: config, task queues, tenants, pipeline config by queue, and contributor data. Defined in **olo-worker-protocol** so plugins, tools, and features can depend on the contract only; it also provides getPipelineConfigByQueue(), getTemporalTargetOrDefault, getTemporalNamespaceOrDefault, and putContributorData/getContributorData. Distinct from **GlobalConfigurationContext** (runtime config store) and **GlobalContext** (olo-worker-execution-tree: one queue config entry).

---

### 3.7 Workflow and activities (olo-worker)

- **OloKernelWorkflowImpl**  
  Implements `OloKernelWorkflow.run(WorkflowInput)`:  
  1. **processInput(workflowInput.toJson())** — deserialize; **tenantId = OloConfig.normalizeTenantId(context.tenantId)**; cache input at **tenant-scoped session key** `config.getSessionDataPrefix(tenantId) + transactionId + ":USERINPUT"` (i.e. `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`) via `OloSessionCache`.  
  2. **getExecutionPlan(queueName, workflowInputJson)** — activity returns a plan JSON (linear true/false, and when linear: configJson, pipelineName, queueName, nodes array with activityType and nodeId). When the tree is **linear** (only SEQUENCE, GROUP, and leaf nodes), the workflow schedules **one Temporal activity per leaf/activity node** via **ExecuteNodeDynamicActivity** (activity type "NODETYPE" or "PLUGIN:pluginRef"); for each node it calls **executeNode(activityType, planJson, nodeId, variableMapJson, queueName, workflowInputJson, dynamicStepsJson)** then **applyResultMapping(planJson, variableMapJson)**. When the tree is **non-linear** (e.g. IF, SWITCH, FORK/JOIN), the workflow calls **runExecutionTree(queueName, workflowInputJson)** once.  
  3. Return the result string (e.g. chat answer).

- **OloKernelActivities**  
  - **processInput(String workflowInputJson)** — deserialize, **tenantId from context**, store at tenant-scoped session key via `OloSessionCache`.  
  - **executePlugin(String pluginId, String inputsJson)** — uses tenant from context; **PluginExecutor** (from **PluginExecutorFactory** in WorkerBootstrapContext) resolves plugin and invokes with **TenantConfig**.  
  - **getChatResponse(String pluginId, String prompt)** — build `{"prompt": prompt}`, call `executePlugin`, return `responseText`.  
  - **getExecutionPlan(String queueName, String workflowInputJson)** — returns plan JSON (linear flag, configJson, pipelineName, queueName, nodes). Used by workflow to decide per-node scheduling vs single runExecutionTree.  
  - **executeNode(...)** — executes one node (leaf or feature-type); activity type is "NODETYPE" or "PLUGIN:pluginRef"; supports **dynamicStepsJson** for planner-generated steps. Handled by **ExecuteNodeDynamicActivity** (DynamicActivity).  
  - **applyResultMapping(String planJson, String variableMapJson)** — applies pipeline resultMapping to variable map; returns workflow result string.  
  - **runExecutionTree(String queueName, String workflowInputJson)** — get **tenantId** from workflow input (normalized); **unknown-tenant check**; **INCR** activeWorkflows; **try {** requestedVersion, LocalContext, **ExecutionConfigSnapshot.of(...)**; **ExecutionEngine.run(snapshot, pluginExecutor, ...)** (QuotaFeature PRE checks tenant config quota; throws **QuotaExceededException** if usage &gt; soft/hard limit) **} finally { DECR activeWorkflows }** (critical: DECR must always run); return result string. Activities implementation **OloKernelActivitiesImpl** takes **PluginExecutorFactory** from **WorkerBootstrapContext** and creates a **PluginExecutor** per run (tenant + node instance cache).

- **ExecuteNodeDynamicActivity**  
  Implements Temporal **DynamicActivity**. Handles per-node activity invocations when the workflow schedules one activity per leaf; activity type in event history is "NODETYPE" or "PLUGIN:pluginRef". Delegates to **OloKernelActivitiesImpl.executeNode(...)**.

---

### 3.8 Execution Engine (olo-worker, single responsibility)

The execution engine is split into five components, each with a single responsibility:

- **VariableEngine**  
  Variable map lifecycle: initializes IN from workflow input, INTERNAL and OUT to null. **Uses ConcurrentHashMap** (safe for ASYNC execution); **null values are stored as a private sentinel** because ConcurrentHashMap does not allow null — **get(name)** returns null when the stored value is the sentinel; **put(name, value)** stores the sentinel for null. When `inputContract.strict` is true, rejects unknown input parameter names. Exposes **getVariableMap()**, **get(name)**, **put(name, value)**. Callers should use **get(name)** for reads so the sentinel is never exposed. Aligns with the [variable execution model](variable-execution-model.md).

- **FeatureResolver**  
  Resolves the effective pre/post feature list for a node. Delegates to `FeatureAttachmentResolver` with scope feature names from the pipeline scope. Single responsibility: build the per-node pre/post hierarchy (by type, queue, scope).

- **NodeExecutor**  
  Executes one node: holds **tenantId** and **tenantConfigMap** (passed from ExecutionEngine). Builds **NodeExecutionContext** with **tenantId** and **tenantConfigMap** so features can use **getTenantId()** and **getTenantConfigMap()**. Resolve **ResolvedPrePost** via FeatureResolver; **runPre(resolved, context, registry)**; **dispatchExecute(node, ...)**; **try { runPostSuccess(...) } catch { runPostError(...) } finally { runFinally(...) }**. Single responsibility: **pre → execute → postSuccess (on success) or postError (on throw) → finally (always)** per node. Dispatches by type (all catalog types including Phase 2/3). See [node-type-catalog.md](node-type-catalog.md); JOIN requires **mergeStrategy**. SUB_PIPELINE requires `ExecutionEngine.run(config, entryPipelineName, ...)`.

- **PluginInvoker**  
  Invokes a PLUGIN node: build plugin inputs from inputMappings and the variable map, call **PluginExecutor.execute(pluginId, inputsJson)** (activity implementation uses **tenantId** to resolve plugin and **TenantConfig** when calling **plugin.execute(inputs, tenantConfig)**), apply outputMappings to the variable map. Single responsibility: plugin invocation and variable read/write for one node.

- **ResultMapper**  
  Applies the pipeline `resultMapping` to the variable map and returns the workflow result string (e.g. first OUT variable value). Single responsibility: map execution variables to the final result.

- **ExecutionEngine**  
  Orchestrator. **Preferred entry: `run(ExecutionConfigSnapshot snapshot, Map inputValues, PluginExecutor pluginExecutor, Map tenantConfigMap)`** — uses the immutable snapshot (no global config reads during run). Legacy **`run(PipelineConfiguration config, ...)`** also supported. Creates VariableEngine, PluginInvoker, **NodeExecutor(..., tenantId, tenantConfigMap)**, runs the tree, then `ResultMapper.apply`. Overload **`run(PipelineDefinition pipeline, ...)`** for SUB_PIPELINE. The activity builds **ExecutionConfigSnapshot** from **LocalContext** and passes **tenantConfigMap** so plugins and features receive tenant-specific config.

- **Runtime execution tree (tree state machine)**  
  **RuntimeExecutionTree** is the single source of truth for a run: one in-memory tree, same object for the whole loop. **Mental model:** workflow/activity = tree state machine; **dispatcher** = state transition engine (dispatch one node, no special cases); **PLANNER** = state mutation producer (only attaches children via **attachChildren**, does not run children inline). Execution loop: **`while ((nextId = tree.findNextExecutable()) != null) { dispatch(node); tree.markCompleted(nextId); }`**. Planner expansion happens **inside** planner node execution; the dispatcher does not branch on planner — it just dispatches. **Do not** execute planner children inline (e.g. `for (child : planner.children) dispatch(child)`); always return to the loop so **findNextExecutable** picks new nodes. For Temporal: if execution were ever split across multiple activities, the tree would need to live in workflow state and be passed to/from activities; currently the full loop runs inside one activity so the tree stays in memory.

---

### 3.8.1 Dynamic tree node creation (PLANNER expansion)

When a **PLANNER** node runs, it produces a **proposed expansion** (semantic child descriptions). The **worker** is the only component that **materializes** nodes and mutates the tree. This keeps the planner **pure and side-effect free** and gives the worker full control over IDs, structure, and lifecycle (clean DDD separation).

#### Roles

| Role | Responsibility |
|------|----------------|
| **Planner** | Proposes expansion: parses planner output (e.g. JSON from an LLM) and returns **`List<NodeSpec>`** plus **variables to inject**. Does **not** construct nodes, assign IDs, or touch the tree. |
| **Worker** | Accepts the proposal and materializes it: validates the request, creates **ExecutionTreeNode** instances (assigns IDs), attaches pipeline/queue features, calls **`RuntimeExecutionTree.attachChildren(parentId, nodes)`**, and returns a read-only **ExpansionResult** (e.g. for logging or downstream use). |

#### Protocol types (olo-worker-protocol)

- **NodeSpec** — Semantic description of a child: `displayName`, `pluginRef`, `inputMappings`, `outputMappings`. No `id`; the planner does not assign IDs.
- **DynamicNodeExpansionRequest** — Request to expand a PLANNER node: `plannerNodeId` (parent that will receive children), `List<NodeSpec> children`.
- **ExpandedNode** — Read-only view of a node after expansion: `id`, `displayName`, `pluginRef`. Exposed to callers who need to know what was created; planner never sees **ExecutionTreeNode** or tree internals.
- **ExpansionResult** — Result of expansion: `List<ExpandedNode> expandedNodes`, `List<NodeSpec> childSpecs`.
- **DynamicNodeFactory** — Interface: **`ExpansionResult expand(DynamicNodeExpansionRequest request)`**. Implemented only in the **worker**; planner and protocol do not implement it.

#### Planner side (olo-planner, olo-planner-a)

- **SubtreeBuilder.buildExpansion(plannerOutputText, plannerNodeId)**  
  Parses the planner output (e.g. JSON array of steps) and returns:
  - **ExpansionBuildResult** = **DynamicNodeExpansionRequest** (with `plannerNodeId` and `List<NodeSpec>`) + **Map&lt;String, Object&gt; variablesToInject** (e.g. `__planner_step_0_prompt`, `__planner_step_1_response`).
- The planner **does not**:
  - Construct **ExecutionTreeNode**.
  - Call **attachChildren** or touch **RuntimeExecutionTree**.
  - Assign node IDs or know tree internals.
- Example: **JsonStepsSubtreeBuilder** parses a JSON array of `{ "toolId", "input": { "prompt" } }`, builds one **NodeSpec** per step (displayName, pluginRef, input/output mappings), and returns **ExpansionBuildResult(request, variablesToInject)**. No execution-tree dependency in the expansion path beyond **ParameterMapping** (protocol/execution-tree contract).

#### Worker side (olo-worker)

- **DynamicNodeFactoryImpl** (in **olo-worker** runtime layer, **not** in olo-worker-execution-tree) implements **DynamicNodeFactory**:
  - Constructor: **RuntimeExecutionTree**, **PipelineFeatureContext**, **NodeFeatureEnricher**, **ExpansionLimits**, **ExpansionState** (limits and state supplied per run by **NodeExecutor**).
  - **expand(request)**:
    1. Validate: `plannerNodeId` non-blank, `children` non-null; skip if no valid child specs.
    2. **Idempotency:** if **tree.hasPlannerExpanded(plannerNodeId)** then return **ExpansionResult** built from existing children (no attach).
    3. **Limits:** enforce **ExpansionLimits** (maxDynamicNodesPerPlanner, maxTotalNodesPerRun, **maxExpansionDepth**, maxPlannerInvocationsPerRun); throw **ExpansionLimitExceededException** if exceeded.
    4. For each **NodeSpec**: create an **ExecutionTreeNode** (worker assigns UUID, type PLUGIN, pluginRef, inputMappings, outputMappings).
    5. Enrich each node with **NodeFeatureEnricher** (same feature attachment as static nodes).
    6. **tree.attachChildren(plannerNodeId, nodes)** — **only** place that mutates the tree for planner expansion.
    7. **tree.markPlannerExpanded(plannerNodeId)**; **expansionState.incrementPlannerInvocations()**.
    8. Return **ExpansionResult(expandedNodes, childSpecs)**.
- **NodeExecutionDispatcher.executePlannerTree**:
  - **Parser path**: resolve **SubtreeBuilder** by name (e.g. `"default"` → JsonStepsSubtreeBuilder). Call **builder.buildExpansion(planResultJson, node.getId())** → **ExpansionBuildResult**. Apply **variablesToInject** to **VariableEngine**. Create **DynamicNodeFactoryImpl(tree, featureContext, nodeFeatureEnricher, expansionLimits, expansionState)** and call **factory.expand(expansionResult.expansionRequest())**. Do **not** call **attachChildren** in the dispatcher; the factory does it.
  - **Subtree-creator path**: plugin returns a list of steps (e.g. `steps`, `variablesToInject`). Build **List&lt;NodeSpec&gt;** from steps (**nodeSpecsFromCreatorSteps**), build **DynamicNodeExpansionRequest(node.getId(), specs)**, create the same factory (with limits and state), call **factory.expand(request)**. Again, no **attachChildren** in the dispatcher.

#### Flow summary

```
  PLANNER node runs (model or interpret-only)
       │
       ▼
  Planner output (e.g. JSON) → SubtreeBuilder.buildExpansion(planText, plannerNodeId)
       │
       ▼
  ExpansionBuildResult { DynamicNodeExpansionRequest(plannerNodeId, List<NodeSpec>), variablesToInject }
       │
       ▼
  Worker: apply variablesToInject to VariableEngine
       │
       ▼
  Worker: DynamicNodeFactoryImpl(tree, featureContext, enricher).expand(request)
       │
       ├── validate request
       ├── if tree.hasPlannerExpanded(plannerNodeId) → return ExpansionResult from existing children (idempotency)
       ├── enforce ExpansionLimits
       ├── for each NodeSpec → create ExecutionTreeNode (worker assigns ID), enrich with features
       ├── tree.attachChildren(plannerNodeId, nodes); tree.markPlannerExpanded(plannerNodeId)
       └── return ExpansionResult(expandedNodes, childSpecs)
       │
       ▼
  Execution loop continues; findNextExecutable() sees new children and runs them in order.
```

#### Planner expansion idempotency (activity retry)

Temporal retries an activity on failure. If the planner node ran and attached children before the crash, a retry would run the same activity again and, without a guard, could **expand the same planner node twice** and attach duplicate children (and duplicate VariableEngine writes). To avoid that, the tree keeps an **already-expanded** marker per planner node:

- **RuntimeExecutionTree** maintains **expandedPlannerNodeIds** (set of planner node ids). After **attachChildren** for a planner parent, the factory calls **markPlannerExpanded(plannerNodeId)**.
- **DynamicNodeFactoryImpl.expand()** checks **tree.hasPlannerExpanded(plannerNodeId)** first. If true (e.g. tree reused on retry), it **does not attach again**; it builds and returns an **ExpansionResult** from the **existing children** of that node (ids, displayName, pluginRef from the tree) so the execution loop can continue. No duplicate nodes, no duplicate attachment.
- This guard is effective when the **same** tree instance is used across retries (e.g. if the tree is ever passed in workflow state or reused by the activity). If each retry builds a fresh tree from the static definition, there are no duplicates but the guard is a no-op for that run.

#### PLANNER and ASYNC execution

**PLANNER nodes are not supported when `executionType == ASYNC`.** The executor throws **IllegalStateException** before dispatching a PLANNER node if the pipeline is ASYNC. Reason: planner expansion (attachChildren, VariableEngine writes) would race with concurrent branches (thread-pool execution, JOIN runs sync). Execution order and variable visibility would be undefined. Pipelines that use PLANNER must use **SYNC** execution.

#### What the planner must NOT do

- Mutate the tree or call **attachChildren**.
- Construct **ExecutionTreeNode** or depend on **RuntimeExecutionTree**.
- Assign node IDs (worker owns ID policy).
- Access bootstrap context directly for expansion; expansion is driven by **SubtreeBuilder** output and worker-owned **DynamicNodeFactory**.

#### What the worker owns

- Tree mutation (**attachChildren** only inside **DynamicNodeFactoryImpl.expand**).
- Structural validation of the expansion request.
- ID policy (e.g. UUID per new node).
- Lifecycle transitions (new nodes become runnable via **findNextExecutable** after attachment).
- **Expansion limits (hard caps)** so dynamic injection is safe — enforced inside **DynamicNodeFactoryImpl.expand()**:
  - **maxDynamicNodesPerPlanner** — max children from a single expansion (default 100).
  - **maxTotalNodesPerRun** — max total nodes in the tree for the run, static + dynamic (default 500).
  - **maxExpansionDepth** — max depth at which expansion is allowed; root depth 0 (default 5).
  - **maxPlannerInvocationsPerRun** — max number of planner expansion invocations per run (default 10).
  Limits are defined by **ExpansionLimits** (default **ExpansionLimits.DEFAULT**); when exceeded, **ExpansionLimitExceededException** is thrown before any tree mutation. **ExpansionState** tracks planner invocation count per run and is passed with limits from **NodeExecutor** into the dispatcher and factory.
- **Planner cycle prevention (depth only):** **maxExpansionDepth** is the only guard against unbounded expansion. Expansion is allowed only when the planner node’s depth is **less than** the limit (root depth 0; default limit 5). If the planner injected a step that itself triggered expansion at greater depth, expansion would be rejected once depth ≥ limit. There are **no** restrictions on which **pluginRef** values may appear in expanded steps; cycle prevention is purely by depth.

#### Feature attachment for dynamically created nodes

New nodes created by **DynamicNodeFactoryImpl** receive the same pipeline and queue-based features as static nodes: **NodeFeatureEnricher** (from bootstrap/worker context) is applied to each new **ExecutionTreeNode** before **attachChildren**. So pipeline scope features and queue-based features (e.g. `-debug` → debug feature) apply to planner-generated steps without any planner-specific enricher in the worker.

---

### 3.9 Workflow input (olo-worker-input)

- **WorkflowInput**  
  Root: `version`, `inputs`, `context`, `routing`, `metadata`. JSON round-trip via `WorkflowInput.fromJson` / `toJson`.

- **InputItem**  
  `name`, `displayName`, `type`, `storage` (LOCAL, CACHE, FILE), `value` or cache/file reference.

- **Routing**  
  `pipeline`, `transactionType`, `transactionId`, **configVersion** (optional). The `pipeline` field identifies the pipeline (and can be the task queue name for debug). **configVersion** pins the run to a specific pipeline config version (execution version pinning). **Resolution policy:** when set, the loaded config's version must match exactly; if requested version != loaded version, **LocalContext.forQueue(..., configVersion)** throws **ConfigVersionMismatchException** and the run fails. No multi-version support; only the version loaded at bootstrap is used.

- **Session storage**  
  Workflow input is stored at a **tenant-scoped** key: **`<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`** (from `OloConfig.getSessionDataPrefix(tenantId)` + transactionId + `":USERINPUT"`). Default prefix is `<tenant>:olo:kernel:sessions:` (placeholder replaced with tenant id). Tenant from `context.tenantId` (normalized). See [workflow-start-session-storage.md](workflow-start-session-storage.md) and [multi-tenant.md](multi-tenant.md).

- **Active workflow count (quota)**  
  The worker **INCR**s Redis key **`<tenantId>:olo:quota:activeWorkflows`** when starting a run and **DECR**s it in a `finally` when the run ends. Use this counter for per-tenant concurrency limits or monitoring. Key from `OloConfig.getActiveWorkflowsQuotaKey(tenantId)`; operations via `OloSessionCache.incrActiveWorkflows(tenantId)` / `decrActiveWorkflows(tenantId)`.

- **QuotaFeature: root-only, once per run**  
  **QuotaFeature** must run only on the root node (first SEQUENCE) and only once per run. Execution order: activity **INCR** activeWorkflows → **try {** ExecutionEngine.run(...) **}** → first node is root SEQUENCE → PRE runs (QuotaFeature checks quota; throws **QuotaExceededException** if over limit) → then node execution and rest of tree → **finally { DECR }**. So QuotaFeature runs before any plugin execution and DECR always runs (no drift). Enable quota only via pipeline **scope.features** (e.g. `"quota"`); do **not** attach it per node (e.g. via node-level `preExecution` or `features`), or it could run on every SEQUENCE and violate the once-per-run contract.

---

## 4. Configuration

- **Environment**  
  See [README.md](../README.md) and `OloConfig`: **OLO_QUEUE**, **OLO_TENANT_IDS**, **OLO_DEFAULT_TENANT_ID**, **OLO_IS_DEBUG_ENABLED**, **OLO_CACHE_HOST/PORT**, **OLO_DB_HOST/PORT**, **OLO_SESSION_DATA**, **OLO_CONFIG_KEY_PREFIX**, **OLO_CONFIG_***, **OLO_MAX_LOCAL_MESSAGE_SIZE**. Per-tenant **quota** (soft/hard limits) is in **olo:tenants** config: `"config": { "quota": { "softLimit": 100, "hardLimit": 120 } }`. Session and config keys use **tenant-first** pattern `<tenantId>:olo:...`. **olo:tenants** supplies tenant list and **TenantConfigRegistry** at bootstrap. Temporal target/namespace from pipeline config `executionDefaults.temporal`. See [multi-tenant.md](multi-tenant.md).

- **Pipeline config files**  
  Under `config/` (or `OLO_CONFIG_DIR`): one file per queue (e.g. `olo-chat-queue-oolama.json`) plus `default.json`. For a `-debug` queue, the loader uses the base queue file (e.g. `olo-chat-queue-oolama.json`) if no `<queue>-debug.json` exists. See [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md).

- **Scope and debug**  
  Pipeline `scope.features` can list `{"id":"debug","displayName":"Debug"}`. When the queue name ends with `-debug`, `FeatureAttachmentResolver` adds `"debug"` to the enabled feature list so the debug feature runs without requiring it in every config.

---

## 5. Data flow summary

1. **Startup**  
   Bootstrap loads env → OloConfig. **Tenant list** from Redis **olo:tenants** (with optional **config** per entry, including **quota.softLimit** / **quota.hardLimit** → **TenantConfigRegistry**); if missing, use **OLO_TENANT_IDS** and write list to **olo:tenants**. For each tenant, load pipeline config into **GlobalConfigurationContext**. Worker **registers plugins per tenant**, **QuotaContext.setSessionCache(sessionCache)**, registers features (e.g. DebuggerFeature, **QuotaFeature**), runs config version validation, constructs **OloKernelActivitiesImpl(sessionCache, allowedTenantIds)**, then starts Temporal workers per queue. Shutdown invokes **ResourceCleanup.onExit()** on all plugins/features (from **PluginRegistry.getAllByTenant()**).

2. **Workflow start**  
   Client starts workflow on a task queue with **WorkflowInput** (inputs, **context.tenantId**, routing.pipeline, transactionId, …). **Unknown-tenant check**: if normalized tenantId is not in **allowedTenantIds**, activity throws **IllegalArgumentException**.

3. **Run**  
   Workflow calls **processInput** (store input at **tenant-scoped** session key), then **runExecutionTree(queueName, workflowInputJson)**. Activity: **tenantId** from workflow context; **INCR** **`<tenantId>:olo:quota:activeWorkflows`**; **try {** requestedVersion, LocalContext, **ExecutionConfigSnapshot.of(...)**; **ExecutionEngine.run(snapshot, ...)** (QuotaFeature PRE checks **tenantConfig.quota**; throws **QuotaExceededException** if over limit); per node **NodeExecutionContext** with **tenantId** and **tenantConfigMap**; PLUGIN nodes: **plugin.execute(inputs, TenantConfig)**; ResultMapper yields result **} finally { DECR activeWorkflows }** (DECR must always run, even on exception or plugin failure, so quota does not drift).

4. **Session**  
   Input is available at **`<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`** for the rest of the run or other services.

---

## 6. Temporal determinism (workflow vs activity)

Temporal workflows must be **deterministic**: the same history must produce the same decisions on replay. Non-deterministic behavior (I/O, time, randomness) belongs in **activities** only.

| Question | Answer |
|----------|--------|
| **Is `ExecutionEngine.run()` always inside an Activity?** | **Yes.** The only call site is `OloKernelActivitiesImpl.runExecutionTree()`, which is an activity method. The workflow (`OloKernelWorkflowImpl.run()`) only invokes `activities.processInput(...)` and `activities.runExecutionTree(...)` via the activity stub; it never calls the execution engine, plugins, or features directly. |
| **Are Redis / config reads only inside activities (or bootstrap)?** | **Yes.** Redis writes (session cache, INCR/DECR active workflows) happen only in activities (`processInput`, `runExecutionTree`). Config is read at **bootstrap** (worker startup) into **GlobalConfigurationContext** and **TenantConfigRegistry**; at activity run time the activity only reads these in-memory structures via `LocalContext.forQueue()` and `TenantConfigRegistry.get()`. No Redis or DB is read from workflow code. |
| **Are feature executions always inside activities?** | **Yes.** Features (e.g. `PreNodeCall`, `FinallyCall`) are invoked from **NodeExecutor**, which is used only by **ExecutionEngine.run()**, which runs only inside the **runExecutionTree** activity. So all feature execution is activity-driven. |

**Contract:** Keep all I/O (Redis, DB, HTTP, file), plugin execution, and feature hooks in **activities**. The workflow must only orchestrate by calling activity stubs and operating on the workflow input/return values (deterministic). See [Temporal determinism](https://docs.temporal.io/workflows#determinism).

---

## 7. Execution failure taxonomy

Execution failures are classified so operations and tooling can treat them consistently. Use this taxonomy for logging, metrics, and run ledger (error_code / failure_type where applicable).

| Classification | When | Effect | Typical handling |
|----------------|------|--------|------------------|
| **PluginFailure** | A plugin’s `execute()` throws (e.g. LLM timeout, embedding API error). | Node fails; exception propagates unless wrapped by RETRY/TRY_CATCH. | Retry (if retryable), alert, ledger records attempt/error_details. |
| **FeatureFailure (internal)** | An internal (kernel-privileged) feature throws in pre/post (e.g. QuotaFeature, Ledger). | Execution fails; exception propagates. | Fix config or feature; treat as policy/guard failure. |
| **FeatureFailure (community)** | A community feature throws in pre/post. | Executor catches, logs, continues (observer-only contract). | Log and monitor; feature must not block execution. |
| **QuotaExceeded** | **QuotaFeature** throws **QuotaExceededException** (usage &gt; soft/hard limit). | Run fails before any plugin runs; DECR still runs in finally. | Back off or scale; tenant quota config. |
| **ConfigMismatch** | **LocalContext.forQueue(..., configVersion)** throws **ConfigVersionMismatchException** (requested version ≠ loaded). | Run fails before execution tree runs. | Align client version with deployed config or redeploy. |
| **UnknownTenant** | Activity rejects workflow: tenantId not in allowed list. | Run fails immediately (e.g. **IllegalArgumentException**). | Validate tenant id; update allowed list or bootstrap. |

Plugin **load** failures (missing JAR, wrong contract, denied package) are separate from execution failures and are documented in the plugin discovery/load flow.

---

## 8. Related documents

- [README.md](README.md) — docs index and sample workflow input  
- [multi-tenant.md](multi-tenant.md) — tenant-scoped Redis keys, global config Map&lt;tenant, Map&lt;queue, config&gt;&gt;, DB tenant_id  
- [versioned-config-strategy.md](versioned-config-strategy.md) — config/plugin/feature contract versions; validation at bootstrap and on config change  
- [variable-execution-model.md](variable-execution-model.md) — IN/INTERNAL/OUT, strict mode, type validation  
- [workflow-start-session-storage.md](workflow-start-session-storage.md) — session key and storing workflow input  
- [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) — pipeline config structure, scope, execution tree, loading, node feature lists (preExecution, postSuccessExecution, postErrorExecution, finallyExecution)  
- [node-type-catalog.md](node-type-catalog.md) — node types, params, merge strategies  
- [../README.md](../README.md) — root project, env vars, build and run  
- [Temporal determinism](https://docs.temporal.io/workflows#determinism) — why workflows must not perform I/O

---

## 9. Non-Goals

The following are **out of scope** for the current architecture. Keeping them explicit prevents accidental drift and sets contributor expectations.

- **No runtime plugin reload** — Plugins are loaded at worker startup. Restart is required to add, remove, or update plugins.
- **No dynamic feature registration** — Features are registered at startup (e.g. **InternalFeatures.registerInternalFeatures(...)**). No API to register or unregister features during a run.
- **No cross-tenant state** — All state (Redis keys, config, plugin registry, session) is tenant-scoped. No shared mutable state across tenants.
- **No workflow-side I/O** — Workflows must not perform Redis, DB, HTTP, or file I/O. All I/O is in activities only (Temporal determinism).
- **No plugin overriding internal plugins** — Community plugins cannot replace or override internal (kernel) plugins. Internal plugins are registered explicitly and take precedence; plugin resolution is by (tenantId, pluginId) without fallback to community for the same id.
