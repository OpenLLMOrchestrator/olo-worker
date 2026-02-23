# OLO Worker: Architecture and Features

This document describes the architecture of the OLO Temporal worker and all features in their current implementation. It is updated to match the codebase: multi-tenant (tenant-scoped Redis and config), **tenant list and tenant-specific config** from Redis `olo:tenants` (or OLO_TENANT_IDS), **tenant-scoped PluginRegistry**, **TenantConfig** / **TenantConfigRegistry** for plugins and features, **unknown-tenant check** at workflow start, **immutable config snapshots** (ExecutionConfigSnapshot per run), **execution version pinning** (optional `routing.configVersion`), **QuotaFeature** (PRE phase, fail-fast from tenant config soft/hard limits), Redis INCR/DECR activeWorkflows for monitoring; feature phases (pre / postSuccess / postError / finally), VariableEngine sentinel for nulls, LocalContext and config keyed by tenant and queue, and version validation at bootstrap and on config change.

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
├─────────────────────────────────────────────────────────────────────────────────┤
│  Global state                                                                     │
│    • GlobalConfigurationContext: Map<tenantKey, Map<queueName, GlobalContext>>    │
│      (read-only; config keyed by tenant then queue)                               │
│    • TenantConfigRegistry: tenantId → TenantConfig (from olo:tenants config)       │
│    • FeatureRegistry: feature name → FeatureEntry (PreNodeCall / PostNodeCall;     │
│      phase routing: isPre, isPostSuccess, isPostError, isFinally)                 │
│    • PluginRegistry: Map<tenantId, Map<pluginId, PluginEntry>> (tenant-scoped)   │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Temporal Worker                                                                  │
│    • One Worker per task queue (OLO_QUEUE + optional -debug queues)               │
│    • Workflows: OloKernelWorkflowImpl                                             │
│    • Activities: OloKernelActivitiesImpl (processInput, executePlugin,           │
│                  getChatResponse, runExecutionTree; optional RunLedger for olo_run/olo_run_node) │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Execution flow (per workflow run)                                                │
│    1. processInput(workflowInputJson) → cache WorkflowInput at tenant-scoped     │
│       session key <tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT       │
│    2. runExecutionTree(queueName, workflowInputJson)                             │
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

---

## 2. Module map

| Module | Purpose |
|--------|---------|
| **olo-annotations** | Declarative metadata: `@OloFeature` (name, phase, applicableNodeTypes, contractVersion), `@OloPlugin`, `@OloUiComponent`; DTOs (FeatureInfo, PluginInfo, UiComponentInfo with contractVersion); **ResourceCleanup** (`onExit()` for plugins/features at worker shutdown); annotation processor generating `META-INF/olo-features.json`, `olo-plugins.json`, `olo-ui-components.json` for bootstrap/UI. |
| **olo-worker-configuration** | `OloConfig` from environment: task queues, **OLO_TENANT_IDS**, **OLO_DEFAULT_TENANT_ID**, cache, DB, session prefix, pipeline config dir/version/retry/key prefix; **normalizeTenantId(String)**; **getSessionDataPrefix(tenantId)**, **getPipelineConfigKeyPrefix(tenantId)**; **TenantConfig** / **TenantConfigRegistry** (incl. **quota.softLimit** / **quota.hardLimit** per tenant); **TenantEntry**; `OloSessionCache` (incr/decr/getActiveWorkflowsCount for `<tenantId>:olo:quota:activeWorkflows`); Redis pipeline config source/sink. |
| **olo-worker-input** | `WorkflowInput`, `InputItem`, **`Routing`** (pipeline, transactionType, transactionId, **configVersion** optional for execution version pinning), `Context`, `Metadata`; storage modes (LOCAL, CACHE, FILE); **InputStorageKeys.cacheKey(tenantId, transactionId, inputName)** for CACHE storage (`olo:<tenantId>:worker:...`). |
| **olo-worker-execution-tree** | Pipeline model: `PipelineConfiguration`, `PipelineDefinition`, `ExecutionTreeNode` (preExecution, postExecution, **postSuccessExecution**, **postErrorExecution**, **finallyExecution**, featureRequired, featureNotRequired), `Scope` (PluginDef, FeatureDef with optional **contractVersion**); `ExecutionTreeConfig`; **ConfigurationLoader.loadConfiguration(tenantKey, queueName, version)** (Redis key tenant-scoped; DB get/put take tenantId); **GlobalConfigurationContext**: Map&lt;tenantKey, Map&lt;queueName, GlobalContext&gt;&gt;; get(tenantKey, queueName), put(tenantKey, queueName, config), loadAllQueuesAndPopulateContext(tenantKey, queueNames, ...). |
| **olo-worker-execution-context** | **LocalContext.forQueue(tenantKey, queueName)** and **forQueue(tenantKey, queueName, configVersion)** (version check for execution version pinning): deep copy of pipeline config from global context. **ExecutionConfigSnapshot** (immutable snapshot: tenantId, queueName, pipelineConfiguration, snapshotVersionId); used by ExecutionEngine so no global config reads during run. |
| **olo-worker-features** | `PreNodeCall`, `PostNodeCall`; **FeatureRegistry** (register by name/phase/applicableNodeTypes/contractVersion; **FeatureEntry**: isPre, isPostSuccess, isPostError, isFinally for phase routing); **FeatureAttachmentResolver** (resolve pre, postSuccess, postError, finally lists from node + queue + scope + registry); **NodeExecutionContext** (nodeId, type, nodeType, attributes, **getTenantId()**, **getTenantConfigMap()** for tenant-specific restrictions/config); **ResolvedPrePost** (getPreExecution, getPostSuccessExecution, getPostErrorExecution, getFinallyExecution; legacy getPostExecution returns union). |
| **olo-feature-debug** | `DebuggerFeature`: PreNodeCall + PostNodeCall; `@OloFeature(name = "debug", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`. before/after log nodeId, type, nodeType, result presence. Registered at startup; auto-attached when queue ends with `-debug`. |
| **olo-feature-quota** | **QuotaFeature**: `@OloFeature(name = "quota", phase = PRE, applicableNodeTypes = {"SEQUENCE"})`. Runs once at pipeline root (first SEQUENCE); reads **OloSessionCache.getActiveWorkflowsCount(tenantId)**; compares with **tenantConfig.quota.softLimit** / **quota.hardLimit**; if usage &gt; limit throws **QuotaExceededException** (fail-fast, no blocking). **QuotaContext** holds session cache (set at worker startup). Optional 5% burst over softLimit. Add `"quota"` to pipeline scope.features to enable. |
| **olo-feature-metrics** | **MetricsFeature**: `@OloFeature(name = "metrics", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`. Lazy self-bootstrapping: static **AtomicReference&lt;MeterRegistry&gt;**; on first execution creates **SimpleMeterRegistry** via CAS and reuses forever (thread-safe, lock-free). In **after()** increments **olo.node.executions** counter with tags **tenant**, **nodeType**. Implements **ResourceCleanup**. Kernel untouched. |
| **olo-run-ledger** | **Run ledger** (env-gated by **OLO_RUN_LEDGER**). **Write-only, fail-safe**. **LedgerStore**, **NoOpLedgerStore**, **JdbcLedgerStore** (tables **olo_run**, **olo_run_node**); **RunLedger**; **LedgerContext**; **NodeAiMetrics**, **NodeReplayMeta**, **NodeFailureMeta**. **RunLevelLedgerFeature** (root); **NodeLedgerFeature** (every node): for **MODEL/PLANNER** node types extracts **token_input_count**, **token_output_count**, **model_name**, **provider** from plugin output (FinOps). Run end: **duration_ms**, **total_nodes**, **total_cost**, **total_tokens** (aggregated from nodes). Node end: **prompt_hash**, **model_config_json**, **tool_calls_json**, **external_payload_ref** (replay); **retry_count**, **execution_stage**, **failure_type** (risk). See docs/run-ledger-schema.md. |
| **olo-worker-plugin** | `ContractType`, **ModelExecutorPlugin** (`execute(inputs)` default, **execute(inputs, TenantConfig)** for tenant-specific params); **PluginRegistry** tenant-scoped: **Map&lt;tenantId, Map&lt;pluginId, PluginEntry&gt;&gt;**; **get(tenantId, pluginId)**, **getModelExecutor(tenantId, pluginId)**, **registerModelExecutor(tenantId, id, plugin)**; getContractVersion(tenantId, pluginId); getAllByTenant() for shutdown. |
| **olo-plugin-ollama** | `OllamaModelExecutorPlugin` (MODEL_EXECUTOR): **execute(inputs, TenantConfig)** uses tenantConfig.get("ollamaBaseUrl"), get("ollamaModel") when set; `/api/chat`; `@OloPlugin`; registered per tenant as e.g. `GPT4_EXECUTOR` (env or tenant config overrides). |
| **olo-worker-bootstrap** | `OloBootstrap.initialize()`: build `OloConfig`; **resolve tenant list** from Redis **olo:tenants** (parse id/name/config via TenantEntry.parseTenantEntriesWithConfig); populate **TenantConfigRegistry** from config; if olo:tenants missing, use OLO_TENANT_IDS and write list to Redis; for each tenant load pipeline config into **GlobalConfigurationContext**; return **BootstrapContext** (config, **getTenantIds()**, flattened map keyed by `"tenant:queue"`). |
| **olo-worker** | `OloWorkerApplication`: bootstrap; **register plugins per tenant**; **QuotaContext.setSessionCache(sessionCache)**; register **QuotaFeature**; **OloKernelActivitiesImpl(sessionCache, allowedTenantIds)**; unknown-tenant check; **INCR** activeWorkflows; **try {** LocalContext, snapshot, **ExecutionEngine.run(snapshot, ...)** **} finally { DECR activeWorkflows }** (DECR must always run); NodeExecutor, PluginInvoker, ResultMapper; shutdown invokes ResourceCleanup on all plugins/features. |

---

## 3. Features in detail

### Feature list (summary)

- **Multi-tenant**: Tenant-scoped Redis/config; tenant list from Redis `olo:tenants` or `OLO_TENANT_IDS`; unknown-tenant check at workflow start.
- **Tenant-specific config**: `TenantConfig` / `TenantConfigRegistry` from `olo:tenants`; plugins and features receive tenant config (e.g. API URLs, restrictions).
- **Immutable config snapshots**: Each run uses **ExecutionConfigSnapshot** (tenantId, queueName, pipeline config deep copy, snapshotVersionId); no global config reads during execution.
- **Execution version pinning**: Optional **routing.configVersion**; **LocalContext.forQueue(tenantKey, queueName, configVersion)** validates version; run fails cleanly if mismatch.
- **Quota (fail-fast)**: **QuotaFeature** (PRE phase, applicable to pipeline root / SEQUENCE): reads current usage from Redis **getActiveWorkflowsCount(tenantId)**; compares with **tenantConfig.quota.softLimit** / **quota.hardLimit**; if exceeded throws **QuotaExceededException** (no blocking). Redis **INCR** at run start, **DECR** in **finally** (always runs so quota does not drift).
- **Plugin/feature contracts**: Tenant-scoped **PluginRegistry**; **ModelExecutorPlugin.execute(inputs, TenantConfig)**; **ResourceCleanup.onExit()** at shutdown.
- **Session storage**: Tenant-first keys `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`.
- **Run ledger** (optional): When **OLO_RUN_LEDGER=true**, RunLevelLedgerFeature (root) and NodeLedgerFeature (every node) are registered. Activity records run start/end with **duration_ms**; run end aggregates **total_nodes**, **total_cost**, **total_tokens**. For **MODEL** and **PLANNER** node types, NodeLedgerFeature persists **token_input_count**, **token_output_count**, **model_name**, **provider** (AI cost tracking). Replay fields (**prompt_hash**, **model_config_json**, **tool_calls_json**, **external_payload_ref**) and failure meta (**retry_count**, **execution_stage**, **failure_type**) supported. Write-only, fail-safe. Tables **olo_run**, **olo_run_node**; indexes for status, pipeline, node_type (see docs/run-ledger-schema.md).
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

- **PreNodeCall**  
  Interface: `void before(NodeExecutionContext context)`. Implement for logic that runs before a tree node executes.

- **PostNodeCall**  
  Interface: `void after(NodeExecutionContext context, Object nodeResult)`. Implement for logic that runs after a tree node executes. The executor invokes it from the appropriate list: postSuccess (result may be non-null), postError (result usually null), or finally (result may be null if the node threw).

- **FeatureRegistry**  
  Singleton. Register feature instances (with `@OloFeature` or explicit metadata: name, phase, applicableNodeTypes, optional contractVersion). Look up by name; **getContractVersion(name)** for config compatibility. **FeatureEntry** exposes **isPre()**, **isPostSuccess()**, **isPostError()**, **isFinally()** so the resolver can route the feature into the correct list. Default phase when not specified: **PRE_FINALLY**. Features that hold resources should implement **ResourceCleanup**; the worker calls **onExit()** on each at shutdown.

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

---

### 3.3 Debug feature (olo-feature-debug)

- **DebuggerFeature**  
  Implements `PreNodeCall` and `PostNodeCall`. Annotated with `@OloFeature(name = "debug", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`.
  - **before**: logs `[DEBUG] pre  nodeId=… type=… nodeType=…` at INFO.
  - **after**: logs `[DEBUG] post nodeId=… type=… nodeType=… resultPresent=…` at INFO.

- **Registration**  
  In `OloWorkerApplication`, `FeatureRegistry.getInstance().register(new DebuggerFeature())` is called at startup.

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
  Enum: **SYNC** (default), **ASYNC**. When ASYNC, all nodes except JOIN run in a thread-pool task; JOIN runs synchronously to merge branches. Set per pipeline via `executionType` in pipeline definition.

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
  4. Build a flattened map `"tenant:queue"` → `PipelineConfiguration` and return **BootstrapContext** (config, **getTenantIds()**, that map).

- **BootstrapContext** (bootstrap return type)  
  Wrapper returned from **OloBootstrap.initialize()**. Holds `OloConfig`, **getTenantIds()**, and the map of composite key → `PipelineConfiguration`; provides **getConfig()**, **getPipelineConfigByQueue()**, **getTemporalTargetOrDefault**, **getTemporalNamespaceOrDefault**. Distinct from **GlobalConfigurationContext** (runtime config store) and **GlobalContext** (olo-worker-execution-tree: one queue’s config entry).

---

### 3.7 Workflow and activities (olo-worker)

- **OloKernelWorkflowImpl**  
  Implements `OloKernelWorkflow.run(WorkflowInput)`:  
  1. **processInput(workflowInput.toJson())** — deserialize; **tenantId = OloConfig.normalizeTenantId(context.tenantId)**; cache input at **tenant-scoped session key** `config.getSessionDataPrefix(tenantId) + transactionId + ":USERINPUT"` (i.e. `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`) via `OloSessionCache`.  
  2. **runExecutionTree(queueName, workflowInput.toJson())** — queueName from `workflowInput.getRouting().getPipeline()`. Activity: **tenantId** from workflow input; unknown-tenant check; **INCR** activeWorkflows; **try {** requestedVersion, LocalContext, snapshot, **ExecutionEngine.run(snapshot, ...)** (QuotaFeature PRE runs inside; throws **QuotaExceededException** if over limit) **} finally { DECR activeWorkflows }** (DECR always runs); return workflow result string.  
  3. Return the result string (e.g. chat answer).

- **OloKernelActivities**  
  - **processInput(String workflowInputJson)** — deserialize, **tenantId from context**, store at tenant-scoped session key via `OloSessionCache`.  
  - **executePlugin(String pluginId, String inputsJson)** — uses default tenant; delegates to **executePlugin(tenantId, pluginId, inputsJson)** with **TenantConfigRegistry.get(tenantId)** passed to **plugin.execute(inputs, tenantConfig)**.  
  - **getChatResponse(String pluginId, String prompt)** — build `{"prompt": prompt}`, call `executePlugin`, return `responseText`.  
  - **runExecutionTree(String queueName, String workflowInputJson)** — get **tenantId** from workflow input (normalized); **unknown-tenant check**; **INCR** activeWorkflows; **try {** requestedVersion, LocalContext, **ExecutionConfigSnapshot.of(...)**; **ExecutionEngine.run(snapshot, ...)** (QuotaFeature PRE checks tenant config quota; throws **QuotaExceededException** if usage &gt; soft/hard limit) **} finally { DECR activeWorkflows }** (critical: DECR must always run, including on exception or plugin failure, so quota does not drift); return result string.

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

---

### 3.9 Workflow input (olo-worker-input)

- **WorkflowInput**  
  Root: `version`, `inputs`, `context`, `routing`, `metadata`. JSON round-trip via `WorkflowInput.fromJson` / `toJson`.

- **InputItem**  
  `name`, `displayName`, `type`, `storage` (LOCAL, CACHE, FILE), `value` or cache/file reference.

- **Routing**  
  `pipeline`, `transactionType`, `transactionId`, **configVersion** (optional). The `pipeline` field identifies the pipeline (and can be the task queue name for debug). **configVersion** pins the run to a specific pipeline config version (execution version pinning); when set, the activity validates that the loaded config version matches.

- **Session storage**  
  Workflow input is stored at a **tenant-scoped** key: **`<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`** (from `OloConfig.getSessionDataPrefix(tenantId)` + transactionId + `":USERINPUT"`). Default prefix is `<tenant>:olo:kernel:sessions:` (placeholder replaced with tenant id). Tenant from `context.tenantId` (normalized). See [workflow-start-session-storage.md](workflow-start-session-storage.md) and [multi-tenant.md](multi-tenant.md).

- **Active workflow count (quota)**  
  The worker **INCR**s Redis key **`<tenantId>:olo:quota:activeWorkflows`** when starting a run and **DECR**s it in a `finally` when the run ends. Use this counter for per-tenant concurrency limits or monitoring. Key from `OloConfig.getActiveWorkflowsQuotaKey(tenantId)`; operations via `OloSessionCache.incrActiveWorkflows(tenantId)` / `decrActiveWorkflows(tenantId)`.

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
| **Are feature executions always inside activities?** | **Yes.** Features (e.g. `PreNodeCall`, `PostNodeCall`) are invoked from **NodeExecutor**, which is used only by **ExecutionEngine.run()**, which runs only inside the **runExecutionTree** activity. So all feature execution is activity-driven. |

**Contract:** Keep all I/O (Redis, DB, HTTP, file), plugin execution, and feature hooks in **activities**. The workflow must only orchestrate by calling activity stubs and operating on the workflow input/return values (deterministic). See [Temporal determinism](https://docs.temporal.io/workflows#determinism).

---

## 7. Related documents

- [README.md](README.md) — docs index and sample workflow input  
- [multi-tenant.md](multi-tenant.md) — tenant-scoped Redis keys, global config Map&lt;tenant, Map&lt;queue, config&gt;&gt;, DB tenant_id  
- [versioned-config-strategy.md](versioned-config-strategy.md) — config/plugin/feature contract versions; validation at bootstrap and on config change  
- [variable-execution-model.md](variable-execution-model.md) — IN/INTERNAL/OUT, strict mode, type validation  
- [workflow-start-session-storage.md](workflow-start-session-storage.md) — session key and storing workflow input  
- [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) — pipeline config structure, scope, execution tree, loading, node feature lists (preExecution, postSuccessExecution, postErrorExecution, finallyExecution)  
- [node-type-catalog.md](node-type-catalog.md) — node types, params, merge strategies  
- [../README.md](../README.md) — root project, env vars, build and run  
- [Temporal determinism](https://docs.temporal.io/workflows#determinism) — why workflows must not perform I/O
