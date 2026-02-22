# OLO Worker: Architecture and Features

This document describes the architecture of the OLO Temporal worker and all features implemented so far.

---

## 1. High-level architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              OLO Worker (JVM)                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Bootstrap (olo-worker-bootstrap)                                                 │
│    • OloConfig from env (OLO_QUEUE, OLO_CACHE_*, OLO_DB_*, OLO_SESSION_DATA, …)  │
│    • Load pipeline config per queue → GlobalConfigurationContext                 │
│    • Order: Redis → DB → <queue>.json → (-debug: base queue .json) → default.json │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Global state                                                                     │
│    • GlobalConfigurationContext: queueName → PipelineConfiguration (read-only) │
│    • FeatureRegistry: feature name → FeatureEntry (PreNodeCall / PostNodeCall)    │
│    • PluginRegistry: plugin id → PluginEntry (e.g. ModelExecutorPlugin)           │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Temporal Worker                                                                  │
│    • One Worker per task queue (OLO_QUEUE + optional -debug queues)                │
│    • Workflows: OloKernelWorkflowImpl                                             │
│    • Activities: OloKernelActivitiesImpl (processInput, executePlugin,           │
│                  getChatResponse, runExecutionTree)                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Execution flow (per workflow run)                                                │
│    1. processInput(workflowInputJson) → cache WorkflowInput at session key         │
│    2. runExecutionTree(queueName, workflowInputJson)                              │
│       • Resolve effective queue (input or activity task queue for -debug)         │
│       • LocalContext.forQueue(effectiveQueue) → tree copy (deep copy of config)    │
│       • Seed variable map from workflow input (input names → values)               │
│       • ExecutionEngine.run(config, entryPipelineName, queue, inputValues, pluginExecutor) │
│       • VariableEngine → FeatureResolver → NodeExecutor → PluginInvoker → ResultMapper │
│       • Traverse tree (uniform): pre → execute → post per node; result string        │
│    3. Return workflow result (e.g. chat answer)                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   Redis (session)      Pipeline config      Ollama (or other
   <prefix><txId>:      (Redis/DB/file)      model-executor plugin)
   USERINPUT
```

---

## 2. Module map

| Module | Purpose |
|--------|---------|
| **olo-annotations** | Declarative metadata: `@OloFeature`, `@OloPlugin`, `@OloUiComponent`; DTOs (FeatureInfo, PluginInfo, UiComponentInfo); annotation processor generating `META-INF/olo-features.json`, `olo-plugins.json`, `olo-ui-components.json` for bootstrap/UI. |
| **olo-worker-configuration** | `OloConfig` from environment (task queues, cache, DB, session prefix, pipeline config dir/version/retry); `OloSessionCache`; Redis pipeline config source/sink. |
| **olo-worker-input** | `WorkflowInput`, `InputItem`, `Routing`, `Context`, `Metadata`; storage modes (LOCAL, CACHE, FILE); session input storage (serialize/store at `<prefix><transactionId>:USERINPUT`). |
| **olo-worker-execution-tree** | Pipeline configuration model: `PipelineConfiguration`, `PipelineDefinition`, `ExecutionTreeNode`, `Scope` (plugins/features), input/output contracts, variable registry; `ExecutionTreeConfig` (JSON, ensure/refresh node IDs); `ConfigurationLoader` (Redis → DB → file → default); `GlobalConfigurationContext` (queue → config). |
| **olo-worker-execution-context** | Per-workflow `LocalContext`: deep copy of pipeline config for a queue from global context (for isolated workflow runs). |
| **olo-worker-features** | Feature contracts: `PreNodeCall`, `PostNodeCall`; `FeatureRegistry`; `FeatureAttachmentResolver` (resolve pre/post lists from node + queue + scope + registry); `NodeExecutionContext`; `ResolvedPrePost`. |
| **olo-feature-debug** | `DebuggerFeature`: pre/post hooks logging node id, type, nodeType (and result presence in post); registered at worker startup; auto-attached when queue name ends with `-debug`. |
| **olo-worker-plugin** | Plugin contracts: `ContractType`, `ModelExecutorPlugin`; `PluginRegistry` (register/get by id, getModelExecutor). |
| **olo-plugin-ollama** | `OllamaModelExecutorPlugin` (MODEL_EXECUTOR): calls Ollama `/api/chat`; `@OloPlugin`; registered as e.g. `GPT4_EXECUTOR` at startup (env: `OLLAMA_BASE_URL`, `OLLAMA_MODEL`). |
| **olo-worker-bootstrap** | `OloBootstrap.initialize()`: build `OloConfig`, load pipeline config for all task queues into `GlobalConfigurationContext`, return `GlobalContext` (config + queue → pipeline config map). |
| **olo-worker** | `OloWorkerApplication`: bootstrap; register Ollama plugin and DebuggerFeature; create Temporal client and one Worker per task queue; register `OloKernelWorkflowImpl` and `OloKernelActivitiesImpl`; run worker. **Execution engine** (package `com.olo.worker.engine`): VariableEngine, FeatureResolver, NodeExecutor, PluginInvoker, ResultMapper, ExecutionEngine (orchestrator). |

---

## 3. Features in detail

### 3.1 Annotations and generated metadata (olo-annotations)

- **@OloFeature**  
  Marks a class as a feature. Attributes: `name`, `phase` (PRE, POST_SUCCESS, POST_ERROR, FINALLY, PRE_FINALLY), `applicableNodeTypes` (e.g. `"*"`, `"MODAL.*"`). Used by the feature registry and by the annotation processor to generate feature metadata.

- **@OloPlugin**  
  Marks a class as an OLO plugin. Attributes: `id`, `displayName`, `contractType`, `description`, `category`, `icon`, `inputParameters`, `outputParameters` (each `@OloPluginParam`). Used for drag-and-drop and variable mapping metadata; aligns with execution tree scope and `PluginRegistry`.

- **@OloUiComponent**  
  Marks a class as a plug-and-play UI component. Attributes: `id`, `name`, `category`, `description`, `icon`. Used to generate UI component discovery JSON.

- **Annotation processor**  
  Scans for `@OloFeature`, `@OloPlugin`, `@OloUiComponent` and writes:
  - `META-INF/olo-features.json` (FeatureInfo: name, phase, applicableNodeTypes, className)
  - `META-INF/olo-plugins.json` (PluginInfo: id, displayName, contractType, params, className)
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
  Singleton. Register feature instances (with `@OloFeature` or explicit metadata). Look up by name; get effective pre/post lists for a node via applicability and phase. Default phase when not specified: **PRE_FINALLY**.

- **FeatureAttachmentResolver**  
  Resolves the effective pre and post feature name lists for a node by merging:
  - Node’s `preExecution`, `postSuccessExecution`, `postErrorExecution`, `finallyExecution` (and legacy `postExecution` into all three post lists)
  - Node’s `features` (routed by each feature’s phase to pre / postSuccess / postError / finally)
  - Pipeline/scope enabled features (when queue name ends with `-debug`, `"debug"` is added)
  - Node’s `featureRequired`
  - Excluding node’s `featureNotRequired`

- **NodeExecutionContext**  
  Immutable context passed to pre/post: `nodeId`, `type`, `nodeType`, optional `attributes`.

- **ResolvedPrePost**  
  Result of resolution: `getPreExecution()`, `getPostSuccessExecution()`, `getPostErrorExecution()`, `getFinallyExecution()` — ordered lists of feature names. The executor runs pre → execute → postSuccess (on success) or postError (on throw) → finally (always).

---

### 3.3 Debug feature (olo-feature-debug)

- **DebuggerFeature**  
  Implements `PreNodeCall` and `PostNodeCall`. Annotated with `@OloFeature(name = "debug", phase = PRE_FINALLY, applicableNodeTypes = {"*"})`.
  - **before**: logs `[DEBUG] pre  nodeId=… type=… nodeType=…` at INFO.
  - **after**: logs `[DEBUG] post nodeId=… type=… nodeType=… resultPresent=…` at INFO.

- **Registration**  
  In `OloWorkerApplication`, `FeatureRegistry.getInstance().register(new DebuggerFeature())` is called at startup.

- **When it runs**  
  Debug runs as part of **tree traversal** in `runExecutionTree`: for every node the resolver attaches the debug feature when the effective queue ends with `-debug`. The activity runs pre → execute node → post uniformly; on a -debug task queue the resolver adds `"debug"` to pre/post for applicable nodes, so debug logs appear for each node (including SEQUENCE and PLUGIN).

---

### 3.4 Plugin system (olo-worker-plugin, olo-plugin-ollama)

- **ContractType**  
  Constants for plugin contract types (e.g. `MODEL_EXECUTOR`, `EMBEDDING`).

- **ModelExecutorPlugin**  
  Interface: `Map<String, Object> execute(Map<String, Object> inputs)`. Aligns with scope `contractType: "MODEL_EXECUTOR"` and tree node `pluginRef` / inputMappings / outputMappings.

- **PluginRegistry**  
  Singleton. Register by id and contract type: `registerModelExecutor(id, plugin)` or `register(id, contractType, plugin)`. Look up: `get(id)`, `getModelExecutor(id)`.

- **OllamaModelExecutorPlugin**  
  Implements `ModelExecutorPlugin`. Input: `"prompt"`. Output: `"responseText"`. Calls `POST <baseUrl>/api/chat` with `model` and messages. Annotated with `@OloPlugin` (id = `GPT4_EXECUTOR`, contractType = `MODEL_EXECUTOR`, input/output parameters). Registered at startup via `OllamaModelExecutorPlugin.registerOllamaPlugin("GPT4_EXECUTOR", baseUrl, model)` (env: `OLLAMA_BASE_URL`, `OLLAMA_MODEL`).

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
  Tree node: `id`, `displayName`, `type` (NodeType), `children`, `params` (type-specific config), `nodeType`, `pluginRef`, `inputMappings`, `outputMappings`, and feature lists.  
  See [node-type-catalog.md](node-type-catalog.md) for purpose and params. Helpers: `withEnsuredUniqueId(node)`, `withRefreshedIds(node)`.

- **Scope**  
  `plugins` (list of PluginDef: id, displayName, contractType, inputParameters, outputParameters), `features` (list of FeatureDef: id, displayName; or strings). Deserializer supports features as strings or objects.

- **Variable execution model (3.x)**  
  See [variable-execution-model.md](variable-execution-model.md): only declared variables (variableRegistry) allowed; IN must match inputContract; INTERNAL initialized null; type validation on inputMappings/outputMappings; OUT must be assigned before completion; unknown variables rejected when inputContract.strict is true.

- **ConfigurationLoader**  
  Load order for a queue: Redis → DB → `<queue>.json` → if queue ends with `-debug` then `<base>.json` → `default.json`. Normalizes config (ensure unique node IDs) and can persist to Redis/DB. Used at bootstrap to fill `GlobalConfigurationContext`.

- **GlobalConfigurationContext**  
  Static map: queue name → `GlobalContext` (queue name + `PipelineConfiguration`). Populated by bootstrap; read by activities and execution context.

- **ExecutionTreeConfig**  
  JSON serialization/deserialization of `PipelineConfiguration`; `ensureUniqueNodeIds(config)`; `refreshAllNodeIds(config)`.

---

### 3.6 Bootstrap (olo-worker-bootstrap)

- **OloBootstrap.initialize()**  
  1. Build `OloConfig` from environment.  
  2. Validate task queues (exit if empty).  
  3. For each task queue, load pipeline config via `ConfigurationLoader` (Redis → DB → file → default) and put into `GlobalConfigurationContext`; file-loaded config is written back to Redis (and DB) so other processes can use it.  
  4. Build a map queue → `PipelineConfiguration` from `GlobalConfigurationContext` and return `GlobalContext` (config + that map).

- **GlobalContext (bootstrap)**  
  Holds `OloConfig` and the map of queue name → `PipelineConfiguration`; provides `getTemporalTargetOrDefault`, `getTemporalNamespaceOrDefault` from pipeline config.

---

### 3.7 Workflow and activities (olo-worker)

- **OloKernelWorkflowImpl**  
  Implements `OloKernelWorkflow.run(WorkflowInput)`:  
  1. `processInput(workflowInput.toJson())` — cache input at session key.  
  2. `runExecutionTree(queueName, workflowInput.toJson())` — queueName from `workflowInput.getRouting().getPipeline()`. The activity uses a **tree copy** from `LocalContext.forQueue(effectiveQueue)` and traverses the execution tree (SEQUENCE → children; PLUGIN → pre features, plugin execution, post features), then applies resultMapping to produce the workflow result.  
  3. Return the result string (e.g. chat answer).

- **OloKernelActivities**  
  - `processInput(String workflowInputJson)` — deserialize, store to session via `OloSessionCache`.  
  - `executePlugin(String pluginId, String inputsJson)` — resolve plugin from `PluginRegistry`, run `plugin.execute(inputs)`, return outputs as JSON.  
  - `getChatResponse(String pluginId, String prompt)` — build `{"prompt": prompt}`, call `executePlugin`, return `responseText` (used by tree traversal for PLUGIN nodes).  
  - **`runExecutionTree(String queueName, String workflowInputJson)`** — run pipeline: resolve effective queue, create **LocalContext** (tree copy), seed variable map from workflow input, **traverse** the execution tree (uniform pre → execute → post per node), apply resultMapping, return workflow result string.

---

### 3.8 Execution Engine (olo-worker, single responsibility)

The execution engine is split into five components, each with a single responsibility:

- **VariableEngine**  
  Variable map lifecycle: initializes IN variables from workflow input, INTERNAL and OUT to null. When `inputContract.strict` is true, rejects unknown input parameter names. Exposes `getVariableMap()`, `get(name)`, `put(name, value)`. Aligns with the [variable execution model](variable-execution-model.md).

- **FeatureResolver**  
  Resolves the effective pre/post feature list for a node. Delegates to `FeatureAttachmentResolver` with scope feature names from the pipeline scope. Single responsibility: build the per-node pre/post hierarchy (by type, queue, scope).

- **NodeExecutor**  
  Executes one node: resolve pre/post via FeatureResolver, run pre hooks, dispatch by type (all catalog types including Phase 2/3), run post hooks. Single responsibility: uniform pre → execute → post per node. See [node-type-catalog.md](node-type-catalog.md); JOIN requires **mergeStrategy** (ALL, ANY, FIRST_WINS, LAST_WINS, MAJORITY, REDUCE, or PLUGIN; PLUGIN uses pluginRef/inputMappings/outputMappings on the JOIN node). SUB_PIPELINE requires execution to be started via `ExecutionEngine.run(config, entryPipelineName, ...)`.

- **PluginInvoker**  
  Invokes a PLUGIN node: build plugin inputs from inputMappings and the variable map, call `PluginExecutor.execute(pluginId, inputsJson)`, apply outputMappings to the variable map. Single responsibility: plugin invocation and variable read/write for one node.

- **ResultMapper**  
  Applies the pipeline `resultMapping` to the variable map and returns the workflow result string (e.g. first OUT variable value). Single responsibility: map execution variables to the final result.

- **ExecutionEngine**  
  Orchestrator. Primary entry: **`run(PipelineConfiguration config, String entryPipelineName, String queueName, Map<String, Object> inputValues, PluginExecutor pluginExecutor)`** — resolves the pipeline by name from config (or uses the first pipeline if the name is missing), creates VariableEngine, PluginInvoker, NodeExecutor (with config for SUB_PIPELINE), runs the tree, then `ResultMapper.apply`. Overload **`run(PipelineDefinition pipeline, String queueName, Map, PluginExecutor)`** runs a single pipeline without config (SUB_PIPELINE nodes no-op). The activity uses the config + entry-pipeline-name form so SUB_PIPELINE works.

---

### 3.9 Workflow input (olo-worker-input)

- **WorkflowInput**  
  Root: `version`, `inputs`, `context`, `routing`, `metadata`. JSON round-trip via `WorkflowInput.fromJson` / `toJson`.

- **InputItem**  
  `name`, `displayName`, `type`, `storage` (LOCAL, CACHE, FILE), `value` or cache/file reference.

- **Routing**  
  `pipeline`, `transactionType`, `transactionId`. The `pipeline` field identifies the pipeline (and can be the task queue name for debug).

- **Session storage**  
  Workflow input is stored at key `OLO_SESSION_DATA + transactionId + ":USERINPUT"` (e.g. Redis). See [workflow-start-session-storage.md](workflow-start-session-storage.md).

---

## 4. Configuration

- **Environment**  
  See [README.md](../README.md) and `OloConfig`: `OLO_QUEUE`, `OLO_IS_DEBUG_ENABLED`, `OLO_CACHE_HOST`, `OLO_CACHE_PORT`, `OLO_DB_HOST`, `OLO_DB_PORT`, `OLO_SESSION_DATA`, `OLO_CONFIG_DIR`, `OLO_CONFIG_VERSION`, `OLO_CONFIG_RETRY_WAIT_SECONDS`, `OLO_CONFIG_KEY_PREFIX`, `OLO_MAX_LOCAL_MESSAGE_SIZE`. Temporal target/namespace come from pipeline config `executionDefaults.temporal`.

- **Pipeline config files**  
  Under `config/` (or `OLO_CONFIG_DIR`): one file per queue (e.g. `olo-chat-queue-oolama.json`) plus `default.json`. For a `-debug` queue, the loader uses the base queue file (e.g. `olo-chat-queue-oolama.json`) if no `<queue>-debug.json` exists. See [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md).

- **Scope and debug**  
  Pipeline `scope.features` can list `{"id":"debug","displayName":"Debug"}`. When the queue name ends with `-debug`, `FeatureAttachmentResolver` adds `"debug"` to the enabled feature list so the debug feature runs without requiring it in every config.

---

## 5. Data flow summary

1. **Startup**  
   Bootstrap loads env → OloConfig; for each task queue loads pipeline config (Redis/DB/file) → GlobalConfigurationContext. Worker registers plugins (e.g. Ollama) and features (e.g. DebuggerFeature), then starts Temporal workers per queue.

2. **Workflow start**  
   Client starts workflow on a task queue (e.g. `olo-chat-queue-oolama` or `olo-chat-queue-oolama-debug`) with `WorkflowInput` (inputs, routing.pipeline, transactionId, …).

3. **Run**  
   Workflow calls `processInput` (store input at session key), then `runExecutionTree(queueName, workflowInputJson)`. Activity uses a tree copy from LocalContext and traverses the execution tree (pre → execute → post per node); PLUGIN nodes call the plugin via `executePlugin`/variable mapping; resultMapping yields the workflow result.

4. **Session**  
   Input is available at `<OLO_SESSION_DATA><transactionId>:USERINPUT` for the rest of the run or other services.

---

## 6. Related documents

- [README.md](README.md) — docs index and sample workflow input  
- [variable-execution-model.md](variable-execution-model.md) — IN/INTERNAL/OUT, strict mode, type validation  
- [workflow-start-session-storage.md](workflow-start-session-storage.md) — session key and storing workflow input  
- [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) — pipeline config structure, scope, execution tree, loading  
- [../README.md](../README.md) — root project, env vars, build and run
