# Pipeline configuration: how to use

The pipeline configuration defines one or more **named pipelines**, each with an input contract, variable registry, scope (plugins/features), and an execution tree. Root-level **plugin** and **feature restrictions** apply across all pipelines.

## Sample file

- **[`sample-pipeline-configuration.json`](sample-pipeline-configuration.json)** – example with one pipeline (`ai-pipeline`).

## Root structure

| Field | Type | Description |
|-------|------|-------------|
| `version` | string | Configuration version (at root level). |
| `executionDefaults` | object | Execution defaults: engine, temporal (target, namespace, taskQueuePrefix), activity (payload, defaultTimeouts, retryPolicy). |
| `pluginRestrictions` | array of string | Allowed plugin IDs at root (empty = no restriction). |
| `featureRestrictions` | array of string | Allowed feature IDs at root (empty = no restriction). |
| `pipelines` | object (map) | Pipelines by name. Key = pipeline name, value = pipeline definition. |

### executionDefaults (root level)

- **engine**: e.g. `"TEMPORAL"`.
- **temporal**: `target` (e.g. `"localhost:7233"`), `namespace` (e.g. `"default"`), `taskQueuePrefix` (e.g. `"olo-"`).
- **activity**:
  - **payload**: `maxAccumulatedOutputKeys`, `maxResultOutputKeys`.
  - **defaultTimeouts**: `scheduleToStartSeconds`, `startToCloseSeconds`, `scheduleToCloseSeconds`.
  - **retryPolicy**: `maximumAttempts`, `initialIntervalSeconds`, `backoffCoefficient`, `maximumIntervalSeconds`, `nonRetryableErrors` (array of string).

## Pipeline definition (each entry in `pipelines`)

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Pipeline name (should match the map key). |
| `workflowId` | string | Workflow identifier (e.g. for Temporal). |
| `inputContract` | object | Input contract (strict mode, parameters). |
| `variableRegistry` | array | Variables with name, type, and scope (IN, INTERNAL, OUT). |
| `scope` | object | Plugins and features available to this pipeline. |
| `executionTree` | object | Root node of the execution tree (SEQUENCE, PLUGIN, etc.). |
| `outputContract` | object | **Out contract**: final result shape to the user (parameters: name, type). |
| `resultMapping` | array | Maps execution variables (e.g. OUT) to output contract parameters (final result). |
| `executionType` | string | **SYNC** (default) or **ASYNC**. When ASYNC, every node except JOIN runs in a worker thread; JOIN runs synchronously to merge. |

### inputContract

- **strict** (boolean): When true, input must satisfy the parameter list.
- **parameters**: Array of `{ "name", "type", "required" }`.

### variableRegistry

Each entry: `{ "name", "type", "scope" }`. **scope** is one of: `IN`, `INTERNAL`, `OUT`. All variables used in the execution tree (inputMappings, outputMappings, resultMapping) must be declared here. See [**3.x Variable Execution Model**](variable-execution-model.md) for rules: IN variables must match inputContract, INTERNAL initialized null, OUT must be assigned before completion, type validation on mappings, unknown variables rejected when strict.

### scope

- **plugins**: Array of plugin definitions (`id`, `contractType`, `inputParameters`, `outputParameters`).
- **features**: Array (e.g. feature flags or IDs).

### outputContract (out contract – final result to user)

Defines the shape of the result returned to the user:

- **parameters**: Array of `{ "name", "type" }` (e.g. `{ "name": "answer", "type": "STRING" }`). These are the fields in the final result.

### resultMapping

Maps execution output variables to output contract parameters. Each entry:

- **variable**: Variable name from execution (typically one with scope OUT in variableRegistry).
- **outputParameter**: Parameter name in the output contract (final result field).

Example: `{ "variable": "finalAnswer", "outputParameter": "answer" }` means the execution variable `finalAnswer` is exposed to the user as the output parameter `answer`.

### executionTree

Tree of nodes. **Node types:** SEQUENCE, IF, SWITCH, ITERATOR, FORK, JOIN, PLUGIN, CASE. See [**Node type catalog**](node-type-catalog.md) for purpose and **params** (e.g. conditionVariable, switchVariable, mergeStrategy, collectionVariable). Each node has:

- **id**, **type** (e.g. `SEQUENCE`, `IF`, `SWITCH`, `ITERATOR`, `FORK`, `JOIN`, `PLUGIN`, `CASE`).
- **children** (for container nodes).
- **params** (optional): type-specific config (e.g. `conditionVariable` for IF, `switchVariable` for SWITCH, `mergeStrategy` for JOIN, `collectionVariable`/`itemVariable` for ITERATOR).
- For **PLUGIN** nodes: **nodeType**, **pluginRef**, **inputMappings**, **outputMappings** (each mapping: `pluginParameter` → `variable`).
- **features** (optional): feature names (shorthand; resolver merges into pre/postSuccess/postError/finally by phase).
- **preExecution** (optional): feature names to run **before** this node.
- **postExecution** (optional): legacy; feature names to run after this node (resolver adds to postSuccess, postError, and finally).
- **postSuccessExecution** (optional): feature names to run **after** this node completes successfully.
- **postErrorExecution** (optional): feature names to run **after** this node throws an exception.
- **finallyExecution** (optional): feature names to run **after** this node (success or error).
- **featureRequired** (optional): features that must be attached (resolver adds by phase).
- **featureNotRequired** (optional): features to **exclude** for this node (e.g. opt out of debug with `["debug"]`).

When the queue name ends with **-debug** and the **debug** feature is in the pipeline/global feature list, the Debugger feature is attached to all nodes (applicableNodeTypes `"*"`) unless the node lists `"debug"` in **featureNotRequired**. Use **FeatureAttachmentResolver.resolve(node, queueName, scopeFeatureNames, registry)** to get the effective **ResolvedPrePost** (pre, postSuccess, postError, finally). The executor runs postSuccess on normal completion, postError on exception, then finally always. See **olo-worker-features** and [architecture-and-features.md](architecture-and-features.md).

## How to load and use (Java)

Use the **olo-worker-execution-tree** module.

### Parse from JSON

```java
import com.olo.executiontree.ExecutionTreeConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;

// From file or string
String json = Files.readString(Path.of("docs/sample-pipeline-configuration.json"));
PipelineConfiguration config = ExecutionTreeConfig.fromJson(json);
```

### Access pipelines by name

```java
// Get a pipeline by name
PipelineDefinition pipeline = config.getPipelines().get("ai-pipeline");
String name = pipeline.getName();           // "ai-pipeline"
String workflowId = pipeline.getWorkflowId();
InputContract inputContract = pipeline.getInputContract();
ExecutionTreeNode root = pipeline.getExecutionTree();

// Iterate all pipelines
config.getPipelines().forEach((name, def) -> {
    // use name and def
});
```

### Root-level version and restrictions

```java
String version = config.getVersion();
List<String> allowedPlugins = config.getPluginRestrictions();   // empty = no restriction
List<String> allowedFeatures = config.getFeatureRestrictions();
```

### Serialize back to JSON

```java
String json = ExecutionTreeConfig.toJson(config);
String pretty = ExecutionTreeConfig.toJsonPretty(config);
```

### Single pipeline (e.g. from API)

```java
// Deserialize one pipeline
PipelineDefinition p = ExecutionTreeConfig.pipelineFromJson(pipelineJson);

// Serialize one pipeline
String pipelineJson = ExecutionTreeConfig.toJson(pipeline);
```

## Loading configuration and runtime config store

The **olo-worker-execution-tree** module provides a load cycle and **GlobalConfigurationContext** (runtime configuration store: Map of tenant → (queue → per-queue config)). **BootstrapContext** is the wrapper returned from `OloBootstrap.initialize()` (OloConfig + flattened config map); **GlobalContext** is the per-queue entry type stored inside GlobalConfigurationContext (one queue’s config in the execution-tree module).

1. **Load order** (per tenant and queue): Redis → DB → `config/<queue>.json` → (if queue ends with `-debug`) `config/<base>.json` → `config/default.json`. Redis key is **tenant-scoped** (tenant-first pattern): `<tenantId>:olo:kernel:config:<queueId>:<version>`.
2. If **no config is found**, wait for an env-configured number of seconds (e.g. `OLO_CONFIG_RETRY_WAIT_SECONDS`) and retry until a valid configuration is found.
3. At **bootstrap**, for each **tenant** (e.g. from `OLO_TENANT_IDS`) and for all queues (from `OLO_QUEUE`), call the loader and store in **GlobalConfigurationContext.put(tenantKey, queueName, config)**. Bootstrap returns a **BootstrapContext** wrapper (OloConfig + flattened config map for validation), not the store itself.
4. **Write-back**: When config is loaded from a **local file**, it is written back to **Redis and DB** via `ConfigSink` so other containers can use it.

### ConfigSource (implement in your app)

Implement `ConfigSource` to read from Redis and DB; the loader uses it for the first two steps:

```java
import com.olo.executiontree.load.ConfigSource;

public class MyConfigSource implements ConfigSource {
    @Override
    public Optional<String> getFromCache(String key) {
        // e.g. redis.get(key)
        return Optional.ofNullable(redis.get(key));
    }

    @Override
    public Optional<String> getFromDb(String queueName, String version) {
        return Optional.ofNullable(db.findConfig(queueName, version));
    }

    @Override
    public Optional<String> getFromDb(String tenantId, String queueName, String version) {
        // query DB for config by tenant_id, queue, version (multi-tenant)
        return Optional.ofNullable(db.findConfig(tenantId, queueName, version));
    }
}
```

### ConfigSink (persist file-loaded config for other containers)

Implement `ConfigSink` to write config to Redis and DB. The loader calls it **only when** config was loaded from a local file (queue or default), so other containers can read it from Redis or DB:

```java
import com.olo.executiontree.load.ConfigSink;

public class MyConfigSink implements ConfigSink {
    @Override
    public void putInCache(String key, String json) {
        redis.set(key, json);
    }

    @Override
    public void putInDb(String queueName, String version, String json) {
        db.upsertConfig(queueName, version, json);
    }
}
```

If you do not pass a `ConfigSink`, file-loaded config is not written back (suitable when all containers share the same config directory or you only use Redis/DB).

### Load one queue and bootstrap all queues

```java
import com.olo.executiontree.load.ConfigurationLoader;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.executiontree.load.GlobalContext;

Path configDir = Path.of("config");
int retryWaitSeconds = Integer.parseInt(System.getenv().getOrDefault("OLO_CONFIG_RETRY_WAIT_SECONDS", "30"));

// Single tenant + queue: load with retry (Redis → DB → file → default). Use tenant-scoped prefix for Redis key.
String tenantId = "default";
String tenantScopedPrefix = tenantId + ":olo:kernel:config";
ConfigurationLoader loader = new ConfigurationLoader(
    myConfigSource, myConfigSink, configDir, retryWaitSeconds, tenantScopedPrefix);
PipelineConfiguration config = loader.loadConfiguration(tenantId, "chat-queue-oolama", "1.0");

// Bootstrap: for each tenant, load all queues into GlobalConfigurationContext (runtime store)
List<String> tenants = List.of("default");
List<String> queues = List.of("chat-queue-oolama", "rag-queue-openai");
String version = "1.0";
for (String tenant : tenants) {
    String prefix = tenant + ":olo:kernel:config";
    GlobalConfigurationContext.loadAllQueuesAndPopulateContext(
        tenant, queues, version, myConfigSource, myConfigSink, configDir, retryWaitSeconds, prefix);
}

// Or use the dedicated bootstrap module (worker does this at startup):
// BootstrapContext ctx = OloBootstrap.initialize();  // reads env, OLO_TENANT_IDS, OLO_QUEUE; loads config per tenant; returns wrapper
// OloConfig config = ctx.getConfig();
// Map<String, PipelineConfiguration> byKey = ctx.getPipelineConfigByQueue();  // keys "tenant:queue"

// Read from runtime config store (GlobalConfigurationContext)
GlobalContext entry = GlobalConfigurationContext.get(tenantId, "chat-queue-oolama");
PipelineConfiguration cfg = entry != null ? entry.getConfiguration() : null;
```

## Example: minimal second pipeline

Add another entry under `pipelines` with a different name:

```json
"pipelines": {
  "ai-pipeline": { ... },
  "rag-pipeline": {
    "name": "rag-pipeline",
    "workflowId": "rag-pipeline",
    "inputContract": { "strict": false, "parameters": [] },
    "variableRegistry": [],
    "scope": { "plugins": [], "features": [] },
    "executionTree": { "id": "root", "type": "SEQUENCE", "children": [] },
    "outputContract": { "parameters": [] },
    "resultMapping": []
  }
}
```

Then load and select by name: `config.getPipelines().get("rag-pipeline")`.
