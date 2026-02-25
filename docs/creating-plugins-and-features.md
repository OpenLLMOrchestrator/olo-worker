# Creating New Plugins and Features

This guide explains how to add a **plugin** (executable capability such as an LLM, vector store, or image generator) and how to add a **feature** (cross-cutting behavior that runs before/after execution tree nodes, such as logging, quota, or metrics).

## Contents

1. [Part 1: Creating a New Plugin](#part-1-creating-a-new-plugin)  
   - Architecture, step-by-step plugin creation, contract types, versioning, **plugin conflict & security rules**, pipeline config
2. [Part 2: Creating a New Feature](#part-2-creating-a-new-feature)  
   - Architecture, step-by-step feature creation, phases, pipeline config
3. [Quick Reference](#quick-reference)

---

## Part 1: Creating a New Plugin

Plugins are **pluggable**: contracts live in **olo-worker-plugin**; implementations are either **internal** (part of the worker fat JAR, explicitly registered) or **community** (JARs in a controlled directory, loaded with a restricted classloader).

### 1.1 Plugin Architecture Overview

| Component | Location | Role |
|-----------|----------|------|
| **Contracts** | `olo-worker-plugin` | `ExecutablePlugin`, `ContractType`, and specific contracts (`ModelExecutorPlugin`, `EmbeddingPlugin`, `VectorStorePlugin`, `ImageGenerationPlugin`) |
| **Discovery** | Plugin module | `PluginManager`: **internal** plugins registered explicitly (not ServiceLoader); **community** plugins loaded from **one directory only** (e.g. `/opt/olo/plugins`), with ServiceLoader used **per isolated JAR** only. No system-level classpath scanning. |
| **Registration** | Worker at startup | All providers (internal + community) are registered with `PluginRegistry` for every tenant |
| **Invocation** | Worker activity | Resolves plugin by `(tenantId, pluginId)` and calls `ExecutablePlugin.execute(inputs, tenantConfig)` |

**Internal plugins** are part of the worker fat JAR and are **not** loaded via ServiceLoader. They are registered explicitly in the plugin module (e.g. `InternalPlugins.createPluginManager()` in `olo-internal-plugins`). The worker only calls that factory and then registers the returned providers with `PluginRegistry`.

**Community plugins** are JARs placed in the directory configured by `OLO_PLUGINS_DIR` (default `/opt/olo/plugins`). **Only this directory is scanned**; only `*.jar` files are loaded. Each JAR is loaded with a **restricted classloader** whose parent allows only:

- **Allowed:** `java.*`, `javax.*`, `jakarta.*`, `com.olo.plugin.*`, `com.olo.config.*`, `org.slf4j.*`
- **Denied:** `com.olo.worker.*`, `com.olo.features.*`, `com.olo.ledger.*`, and any other internal packages

Reflection (e.g. `Class.forName("com.olo.worker.SomeInternalClass")`) is not a backdoor: the classloader throws `ClassNotFoundException` for denied packages. The parent loader exposes only API modules, not worker internals.

#### 1.1.1 Plugin instance lifecycle (per-node, not singleton)

Plugin instances are **not singletons**. The worker creates **one instance per execution tree node** that references the plugin: if the same plugin is used in two different nodes (e.g. two `PLUGIN` nodes with `pluginRef: "GPT4_EXECUTOR"`), each node gets its own instance. When the **same node** runs again (e.g. inside an ITERATOR loop), the **same instance** is reused for that node (it is "mounted" to that node for the duration of the run).

- **Per node in the tree**: New instance per node. Different nodes → different instances.
- **Same node in a loop**: Same instance for that node for the whole run (run-scoped cache keyed by `nodeId`).

To support this, the registry stores the **PluginProvider** (not a single plugin instance). When the worker needs a plugin for a node, it calls **createPlugin()** on the provider and caches the instance by `nodeId` for the run. Override **createPlugin()** in your provider to return a **new instance** each time (e.g. `return new MyServicePlugin(baseUrl, model);`). The default **createPlugin()** returns **getPlugin()**, so existing providers that do not override it keep one shared instance per node (still one per node from the cache, but each node gets the same underlying instance if you don’t override). For true per-node isolation, override **createPlugin()** to return a new instance.

#### 1.1.2 Threading and state model

- **Plugin instance is run-scoped.**  
  The same instance may be used for multiple invocations only when the **same node** runs again in the same run (e.g. inside an ITERATOR). Different nodes never share an instance. Do not assume cross-node instance sharing.

- **Execution engine is single-threaded per run.**  
  For a given run, the engine invokes nodes **sequentially**. One node’s `execute()` completes before the next node’s `execute()` is called. Within a run, there is no concurrent invocation of the same plugin instance.

- **Is a plugin allowed to maintain state?**  
  **Yes**, but only for the lifetime of that instance. A plugin may use mutable fields for the duration of a run (e.g. to accumulate data across loop iterations for the **same** node). It must **not** rely on or assume that state is visible to other nodes or to other runs; the engine does not guarantee that.

- **Is a plugin required to be thread-safe?**  
  **Today: no.** The engine does not invoke a plugin from multiple threads for the same run. **If the engine later introduces parallel node execution** (e.g. concurrent execution of sibling nodes), plugins may be invoked from multiple threads. To avoid future race conditions and to be forward-compatible, **implement plugins as thread-safe** where they use mutable state (e.g. synchronize or use thread-safe data structures). Do not assume single-threaded invocation in the long term.

- **Do not assume execution order across loop iterations.**  
  Today the engine runs sequentially, so the same node in a loop sees invocations in order. **Future engine evolution** (e.g. parallel execution of siblings) may change this. Plugins must **not** rely on execution order across loop iterations; stateful accumulation (e.g. across iterations of the same node) may become unsafe under parallel execution. Implement in a thread-safe way and avoid order-dependent state.

- **Summary:**  
  - Plugin instance = run-scoped (per node; same node in a loop reuses the same instance).  
  - Engine invokes a given node sequentially; no concurrent calls to the same instance today.  
  - Plugins must **not** assume cross-node instance sharing.  
  - Plugins must **not** assume execution order across loop iterations (forward-compatibility with parallel execution).  
  - Plugins should be **thread-safe** if they hold mutable state, so they remain safe if the engine adds parallel execution later.

### 1.2 Step-by-Step: Create a New Plugin

#### Step 1: Create the plugin module

1. Create a new Gradle subproject, e.g. `olo-plugin-my-service`.
2. Add it to **settings.gradle**:
   ```gradle
   include 'olo-plugin-my-service'
   ```
3. Create **olo-plugin-my-service/build.gradle** (adjust dependencies if you need more than the plugin contract):

   ```gradle
   plugins {
       id 'java'
   }

   group = 'com.olo'
   version = '1.0.0-SNAPSHOT'

   java {
       sourceCompatibility = JavaVersion.VERSION_17
       targetCompatibility = JavaVersion.VERSION_17
   }

   repositories {
       mavenCentral()
   }

   dependencies {
       implementation project(':olo-worker-configuration')
       implementation project(':olo-worker-plugin')
       implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.1'
       implementation 'org.slf4j:slf4j-api:2.0.9'
       testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
       testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
   }

   tasks.named('test') {
       useJUnitPlatform()
   }
   ```

#### Step 2: Choose or extend the contract

All plugins must implement **ExecutablePlugin** (a single method: `execute(Map<String, Object> inputs, TenantConfig tenantConfig) → Map<String, Object>`).

- Use an **existing contract** if it fits:
  - **ModelExecutorPlugin** – LLM/chat: input `prompt` → output `responseText`, etc.
  - **EmbeddingPlugin** – input `text` or `texts` → output `embeddings`, `model`.
  - **VectorStorePlugin** – input `operation` (`create_collection`, `upsert`, `query`, `delete`), `collection`, and operation-specific keys → output depends on operation.
  - **ImageGenerationPlugin** – input `prompt`, optional `negativePrompt`, `width`, `height`, `steps`, `seed` → output `imageBase64` and/or `imageUrl`, `seed`.

- If none fit, implement **ExecutablePlugin** directly. Use an existing **ContractType** constant, or add a new one in **olo-worker-plugin** (`ContractType.java`, e.g. `public static final String MY_CONTRACT = "MY_CONTRACT";`). Document the input/output map shape in your plugin class and in the contract (or package-info).

#### Step 3: Implement the plugin class

- Implement the chosen interface (e.g. `ModelExecutorPlugin` or `ExecutablePlugin`).
- Read configuration from **env** and/or **TenantConfig** (e.g. base URL, model name).
- In `execute(Map, TenantConfig)`:
  - Read inputs from the map (e.g. `inputs.get("prompt")`).
  - Call your external service (HTTP, SDK, etc.).
  - Return a `Map` of output names to values (e.g. `responseText`, `promptTokens`).

Example (skeleton):

```java
package com.olo.plugin.myservice;

import com.olo.config.TenantConfig;
import com.olo.plugin.ExecutablePlugin;
import java.util.Map;

public final class MyServicePlugin implements ExecutablePlugin {

    private final String baseUrl;

    public MyServicePlugin(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:8080";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("myServiceBaseUrl") != null
                ? String.valueOf(tenantConfig.get("myServiceBaseUrl")).trim() : baseUrl;
        String prompt = inputs != null ? String.valueOf(inputs.get("prompt")).trim() : "";
        // ... call external API, parse response ...
        return Map.of("responseText", response, "modelId", model);
    }
}
```

- Optional: implement **ResourceCleanup** and override `onExit()` if the plugin holds resources (e.g. HTTP client, connections). The worker calls `onExit()` on all registered plugins at shutdown.

#### Step 4: Implement PluginProvider (SPI)

- Create a class that implements **PluginProvider**.
- In the **no-arg constructor**, read environment variables (e.g. `MY_SERVICE_BASE_URL`, `MY_SERVICE_MODEL`) and construct your plugin instance.
- Return:
  - **getPluginId()** – unique id that will be used in pipeline scope and in execution tree nodes as `pluginRef` (e.g. `"MY_SERVICE_EXECUTOR"`).
  - **getContractType()** – one of `ContractType.MODEL_EXECUTOR`, `ContractType.EMBEDDING`, `ContractType.VECTOR_STORE`, `ContractType.IMAGE_GENERATOR`, or your custom constant.
  - **getPlugin()** – the plugin instance.
- Optional: override **isEnabled()** and return `true` only when a required env var is set (e.g. `MY_SERVICE_BASE_URL`), so the plugin is only registered when the user has configured it.

Example:

```java
package com.olo.plugin.myservice;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

public final class MyServicePluginProvider implements PluginProvider {

    private final String baseUrl;
    private final String model;
    private final MyServicePlugin plugin;  // for getPlugin() / shutdown cleanup

    public MyServicePluginProvider() {
        String u = System.getenv("MY_SERVICE_BASE_URL");
        baseUrl = (u != null && !u.isBlank()) ? u.trim() : "http://localhost:8080";
        String m = System.getenv("MY_SERVICE_MODEL");
        model = (m != null && !m.isBlank()) ? m.trim() : "default";
        this.plugin = new MyServicePlugin(baseUrl, model);
    }

    @Override
    public String getPluginId() {
        return "MY_SERVICE_EXECUTOR";
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }

    @Override
    public ExecutablePlugin createPlugin() {
        return new MyServicePlugin(baseUrl, model);  // new instance per tree node
    }

    @Override
    public boolean isEnabled() {
        String u = System.getenv("MY_SERVICE_BASE_URL");
        return u != null && !u.isBlank();
    }
}
```

#### Step 5: Register the provider (internal vs community)

**Internal plugins** (shipped in the fat JAR): They are **not** discovered via ServiceLoader. The list of internal providers is fixed in the plugin module (e.g. `InternalPlugins.createPluginManager()` in `olo-internal-plugins`). To add an internal plugin, add it to that module’s dependencies and register it in `InternalPlugins.createPluginManager()`.

**Community plugins** (JARs in `OLO_PLUGINS_DIR`): Create the file **olo-plugin-my-service/src/main/resources/META-INF/services/com.olo.plugin.PluginProvider** and add one line with the fully qualified class name of your provider:
  ```
  com.olo.plugin.myservice.MyServicePluginProvider
  ```
  ServiceLoader is used **per isolated JAR only**: each JAR in the plugins directory is loaded with its own classloader, and only that JAR is scanned for `PluginProvider` implementations. There is **no system-level classpath scanning**. Do not reintroduce worker- or system-level scanning of the classpath for plugins (governance: internal = explicit registration; community = directory-only, per-JAR ServiceLoader).

#### Step 6: Use the plugin in a pipeline

1. **Pipeline config** (e.g. `config/my-pipeline.json`): in the pipeline’s `scope.plugins`, add an entry with the same `id` as your **getPluginId()** and the same `contractType`:
   ```json
   "scope": {
     "plugins": [
       {
         "id": "MY_SERVICE_EXECUTOR",
         "displayName": "My Service",
         "contractType": "MODEL_EXECUTOR",
         "inputParameters": [ { "name": "prompt", "type": "STRING", "required": true } ],
         "outputParameters": [ { "name": "responseText", "type": "STRING" } ]
       }
     ],
     "features": []
   }
   ```
2. **Execution tree**: add a node with `type: "PLUGIN"`, `pluginRef: "MY_SERVICE_EXECUTOR"`, and `inputMappings` / `outputMappings` that map variables to your plugin’s input/output parameter names:
   ```json
   {
     "id": "myNode",
     "type": "PLUGIN",
     "nodeType": "MODEL_EXECUTOR",
     "pluginRef": "MY_SERVICE_EXECUTOR",
     "inputMappings": [ { "pluginParameter": "prompt", "variable": "userQuery" } ],
     "outputMappings": [ { "pluginParameter": "responseText", "variable": "finalAnswer" } ]
   }
   ```

### 1.3 Contract Types and I/O Summary

| ContractType | Typical inputs | Typical outputs |
|--------------|----------------|------------------|
| MODEL_EXECUTOR | `prompt` | `responseText`, `promptTokens`, `completionTokens`, `modelId` |
| EMBEDDING | `text` or `texts` | `embeddings` (list of vectors), `model` |
| VECTOR_STORE | `operation`, `collection`, plus operation-specific | e.g. `results` for query, `ok` for upsert/delete |
| IMAGE_GENERATOR | `prompt`, `negativePrompt`, `width`, `height`, `steps`, `seed` | `imageBase64`, `imageUrl`, `seed` |
| REDUCER | Any number of label → value (e.g. `"X Model"` → variable from model X) | `combinedOutput` (single string combining all) |

#### 1.3.1 Join reducer (OUTPUT_REDUCER, olo-join-reducer)

The **reducer** is a **JOIN**-type plugin that clubs the output of all JOIN branch children into one formatted string. Implementation lives in the dedicated module **olo-join-reducer**; the built-in **OUTPUT_REDUCER** (contract type REDUCER) produces lines like:

```text
Output From X Model:"xyz"
Output From Y Model:"abc"
```

**How to use it (JOIN node with mergeStrategy REDUCE):**

1. **Scope:** Add the reducer to `scope.plugins` with `id: "OUTPUT_REDUCER"` and `contractType: "REDUCER"`.
2. **Execution tree:** Use a **JOIN** node (not PLUGIN) with **mergeStrategy: "REDUCE"**, **pluginRef: "OUTPUT_REDUCER"**, and **inputMappings** / **outputMappings**. Each JOIN child (e.g. a branch from a FORK) writes to a variable; map those variables to reducer input parameters (labels) and map `combinedOutput` to an output variable.
3. **inputMappings:** Map each branch’s **output variable** to a **plugin parameter** that acts as the label (e.g. variable `branch1Response` → `"X Model"`, `branch2Response` → `"Y Model"`).
4. **outputMappings:** Map plugin output `combinedOutput` to a variable (e.g. `reducedOutput`) for downstream use.

Example: FORK with two children (e.g. two model nodes) each writing to `modelXResponse` and `modelYResponse`; JOIN with mergeStrategy REDUCE, pluginRef OUTPUT_REDUCER, inputMappings `modelXResponse` → `X Model`, `modelYResponse` → `Y Model`, outputMappings `combinedOutput` → `reducedOutput`. You can also use a PLUGIN node with pluginRef OUTPUT_REDUCER after a SEQUENCE of plugins if you prefer a linear flow.

### 1.4 Plugin versioning and capability metadata (evolution path)

The plugin SPI and registry support versioning and capability metadata so you can evolve toward semantic compatibility, multi-team deployments, and enterprise audit without breaking current behavior.

**Invariant:** `pluginId` must be unique per tenant, regardless of version. Version is **metadata today, not a resolution key**. Resolution is by `(tenantId, pluginId)` only. If later you allow `pluginId + version` as a composite key, you must define: how the pipeline selects version, default resolution strategy, and conflict behavior.

| API | Purpose |
|-----|--------|
| **PluginProvider.getVersion()** | Plugin/contract version (e.g. `"1.0"`, `"2.1.0"`). Default `"1.0"`. Used at registration and stored in **PluginEntry** for compatibility checks and logging. |
| **PluginProvider.getCapabilityMetadata()** | Optional map for audit and capability (e.g. `supportedOperations`, `vendor`, `apiLevel`). Default empty. Stored in **PluginEntry** and exposed for tooling/audit. |
| **PluginEntry.getContractVersion()** | Version stored at registration (from `getVersion()`). |
| **PluginEntry.getCapabilityMetadata()** | Immutable copy of metadata stored at registration (from `getCapabilityMetadata()`). |

**Current behavior:** Resolution is still by `(tenantId, pluginId)` only. Config compatibility validation can already use `getContractVersion()` to ensure pipeline scope expects a compatible plugin version.

**Future evolution (not implemented yet):**

- **pluginId + version** as a composite key when multiple implementations of the same logical plugin coexist (e.g. two teams deploying different versions of the same plugin id).
- **Semantic compatibility** rules (e.g. require same major version, or a compatibility matrix).
- **Audit:** Plugin versions are **persisted for audit** in the run ledger. When `OLO_RUN_LEDGER=true`, each run’s config snapshot in **olo_config** includes **plugin_versions** (JSON map of pluginId → contract version) written at run start and immutable for the run. See [run-ledger-schema.md](run-ledger-schema.md#audit-persistence-of-plugin-version).

Override `getVersion()` and/or `getCapabilityMetadata()` in your **PluginProvider** to participate in this path; existing providers continue to work with defaults.

### 1.5 Plugin conflict & security rules

Governance: these rules are part of the architecture, not only the implementation. Do not weaken them.

1. **Internal plugins** are registered explicitly; there is **no runtime discovery** of internal plugins (no classpath scanning). **Internal plugin classes must originate from the worker fat JAR (validated via CodeSource).** External JARs cannot be treated as internal. This prevents reintroducing `ServiceLoader.load(...)` or other classpath scanning in the internal context.
2. **Community plugins** are loaded **only from `OLO_PLUGINS_DIR`**. No other directories or system classpath are scanned.
3. **Duplicate plugin IDs:** **Internal wins.** Providers are registered in order: internal first, then community. If a community plugin declares the same `pluginId` as an internal plugin, the community duplicate is **skipped** (logged); the worker continues. Community plugins **cannot override** internal plugins.
4. **Community plugins cannot access worker internals.** The restricted classloader allows only `java.*`, `javax.*`, `jakarta.*`, `com.olo.plugin.*`, `com.olo.config.*`, `org.slf4j.*`. Denied: `com.olo.worker.*`, `com.olo.features.*`, `com.olo.ledger.*`, and any other internal packages.
5. **Reflection boundary.** Even with a restricted parent, a plugin could try `Class.forName("com.olo.worker.SomeInternalClass")`. The classloader must throw `ClassNotFoundException` for any denied class. The parent exposes only the API modules above; if it delegated to the application classloader for other packages, reflection would become a backdoor. This is enforced in `RestrictedPluginClassLoader`.
6. **Plugin resolution is deterministic and immutable after startup.** The set of plugins is fixed once registration completes; no runtime classpath scanning is performed.

#### 1.5.1 Plugin load failure behavior (operational)

Operations must know whether a plugin failure is fatal or not. The following is the defined behavior.

| Failure case | Internal plugin | Community plugin |
|--------------|-----------------|------------------|
| Classloader throws (e.g. JAR corrupt, denied class) | **Fatal:** worker fails at startup | **Log + skip:** JAR is skipped; worker continues |
| Provider constructor / instantiation fails | **Fatal:** worker fails at startup | **Log + skip:** that provider is skipped |
| `isEnabled()` throws | **Fatal:** worker fails during registration | **Log + skip:** that provider is skipped |
| Duplicate plugin ID (already registered) | **Fatal:** worker fails at startup | **Log + skip:** duplicate is skipped (internal wins) |
| ContractType mismatch or invalid registration | **Fatal:** worker fails at startup | **Log + skip:** that provider is skipped |

- **Internal plugin failure → fatal.** The worker does not start. Fix or remove the internal plugin.
- **Community plugin failure → log + skip.** The failure is logged (error level); the worker continues with the remaining plugins. This is **not** fatal for production unless you explicitly configure community plugins as required (e.g. a future `OLO_PLUGINS_REQUIRED=true` or similar would make any community load/registration failure fatal).

Ensure failures are **logged** so operations can see why a plugin was skipped. Do not fail the worker entirely for a single bad community JAR unless explicitly required.

### 1.6 Reference: Existing Plugin Modules

- **olo-plugin-ollama** – Model executor (Ollama `/api/chat`). Provider: `OllamaPluginProvider`; id `GPT4_EXECUTOR`; always enabled.
- **olo-plugin-litellm** – Model executor (OpenAI-compatible API). Provider: `LiteLLMPluginProvider`; id `LITELLM_EXECUTOR`; enabled when `LITELLM_BASE_URL` is set.
- **olo-plugin-qdrant** – Vector store. Provider: `QdrantPluginProvider`; id `QDRANT_VECTOR_STORE`; enabled when `QDRANT_BASE_URL` is set.
- **olo-plugin-embedding-ollama** – Embeddings (Ollama `/api/embed`). Provider: `OllamaEmbeddingPluginProvider`; id `OLLAMA_EMBEDDING`; enabled when `OLLAMA_EMBEDDING_MODEL` is set.
- **olo-plugin-image-sd**, **olo-plugin-image-comfyui**, **olo-plugin-image-invokeai** – Image generation. Each has a provider and is enabled when its base URL env var is set.

---

## Part 2: Creating a New Feature

Features are **cross-cutting**: they run before and/or after execution tree nodes (e.g. logging, quota checks, metrics). Features are **registered explicitly** in the worker application; adding a new feature requires a new feature module, implementing the feature hooks, and **registering the feature in OloWorkerApplication** and adding the feature module to the worker’s dependencies.

### 2.1 Feature Architecture Overview

| Component | Location | Role |
|-----------|----------|------|
| **Contracts** | `olo-worker-features` | `PreNodeCall` (`before(NodeExecutionContext)`), `FinallyCall` (`afterFinally(...)`), `PreFinallyCall` (before + afterSuccess/afterError/afterFinally) |
| **Metadata** | `olo-annotations` | `@OloFeature(name, phase, applicableNodeTypes, contractVersion)` |
| **Registry** | `olo-worker-features` | `FeatureRegistry`: register by instance; lookup by name; resolve by node type and pipeline scope |
| **Registration** | Worker / olo-internal-features | Internal: **InternalFeatures.registerInternalFeatures(...)** (in **olo-internal-features**). Community: `FeatureRegistry.getInstance().registerCommunity(new MyFeature())` (or `registerInternal(...)` for kernel-privileged). |
| **Invocation** | Worker execution engine | For each node, resolve pre/post feature lists; then call `before()` / `after()` according to phase and success/error |

**Phases** (`FeaturePhase`):

- **PRE** – runs only before the node.
- **POST_SUCCESS** – runs after the node when it completes successfully.
- **POST_ERROR** – runs after the node when it throws.
- **FINALLY** – runs after the node (success or error).
- **PRE_FINALLY** – runs both before and after (like PRE + FINALLY).

**When to use which phase:**

- **POST_SUCCESS / POST_ERROR** — Use for **heavy lifting**: logic that may throw, has significant side effects, or must react specifically to success vs error (e.g. conditional persistence, retry bookkeeping, error reporting). Implement **PostSuccessCall** and/or **PostErrorCall** (or **PreFinallyCall** with afterSuccess/afterError).
- **FINALLY / PRE_FINALLY** — Use for **non–exception-prone** code to achieve the functionality: logging, metrics, lightweight cleanup, or any logic that should run regardless of outcome without throwing. Implement **FinallyCall** or **PreFinallyCall** (afterFinally). This keeps the finally phase predictable and avoids exception handling inside post hooks.

**Applicability:** A feature can be limited to certain node types via `applicableNodeTypes` (e.g. `"SEQUENCE"`, `"PLUGIN"`, `"*"` for all). Pipeline scope lists which features are **enabled** for that pipeline (by feature id). The resolver merges node-level lists, scope features, and debug-queue behavior.

#### 2.1.1 Feature privilege (internal vs community)

Features have two privilege levels (like plugins).

- **Internal (kernel-privileged):** Can block execution, mutate context, affect failure semantics, persist ledger, enforce quotas, audit, run in any phase. Registered via **registerInternal(...)** or **register(...)**. Olo-controlled; internal features are aggregated in **olo-internal-features** and registered in **InternalFeatures.registerInternalFeatures(...)** at worker startup. If an internal feature throws in a pre hook, execution fails.
- **Community (restricted):** Must be **observer-only**: may read context, log, emit metrics, append attributes; must **not** block execution, change the execution plan, throw to fail the run, or **mutate execution state**. **NodeExecutionContext** is immutable (read-only); community features must not mutate it. Registered via **FeatureRegistry.registerCommunity(...)**. If a community feature throws, the executor catches, logs, and continues. Use **ObserverPreNodeCall** / **ObserverPostNodeCall** in contracts to document observer-only semantics.

See **docs/architecture-and-features.md** § 3.2.2 for the full contract.

### 2.2 Step-by-Step: Create a New Feature

#### Step 1: Create the feature module

1. Create a new Gradle subproject, e.g. `olo-feature-myfeature`.
2. Add it to **settings.gradle**:
   ```gradle
   include 'olo-feature-myfeature'
   ```
3. Create **olo-feature-myfeature/build.gradle**:

   ```gradle
   plugins {
       id 'java'
   }

   group = 'com.olo'
   version = '1.0.0-SNAPSHOT'

   java {
       sourceCompatibility = JavaVersion.VERSION_17
       targetCompatibility = JavaVersion.VERSION_17
   }

   repositories {
       mavenCentral()
   }

   dependencies {
       implementation project(':olo-annotations')
       implementation project(':olo-worker-features')
       implementation 'org.slf4j:slf4j-api:2.0.9'
       annotationProcessor project(':olo-annotations')
       testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
       testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
   }

   tasks.named('test') {
       useJUnitPlatform()
   }
   ```

#### Step 2: Implement the feature class

- Annotate the class with **@OloFeature**:
  - **name** – unique id (e.g. `"myfeature"`). Used in pipeline scope and in the registry.
  - **phase** – when to run: `PRE`, `POST_SUCCESS`, `POST_ERROR`, `FINALLY`, or `PRE_FINALLY`.
  - **applicableNodeTypes** – node type patterns (e.g. `"*"` for all, `"SEQUENCE"`, `"PLUGIN"`).
  - **contractVersion** – optional; for config compatibility (e.g. `"1.0"`).
- Implement **PreNodeCall** and/or **FinallyCall** (or **PostSuccessCall** / **PostErrorCall** / **PreFinallyCall**) depending on phase:
  - **before(NodeExecutionContext context)** – pre logic (e.g. quota check, logging).
  - **afterFinally(NodeExecutionContext context, Object nodeResult)** – post logic that runs after the node completes (success or error); for phase-specific hooks use **PostSuccessCall**, **PostErrorCall**, or **PreFinallyCall**.
- Optional: implement **ResourceCleanup** and override **onExit()** for shutdown (e.g. clear thread locals, close resources).

**NodeExecutionContext** provides: `nodeId`, `type`, `nodeType`, `tenantId`, `tenantConfigMap`, `queueName`, `pluginId`, `executionSucceeded`, `attributes`.

Example:

```java
package com.olo.features.myfeature;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.annotations.ResourceCleanup;
import com.olo.features.FinallyCall;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PreNodeCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OloFeature(
    name = "myfeature",
    phase = FeaturePhase.PRE_FINALLY,
    applicableNodeTypes = { "*" },
    contractVersion = "1.0"
)
public final class MyFeature implements PreNodeCall, FinallyCall, ResourceCleanup {

    private static final Logger log = LoggerFactory.getLogger(MyFeature.class);

    @Override
    public void before(NodeExecutionContext context) {
        log.info("[myfeature] pre nodeId={} type={}", context.getNodeId(), context.getType());
    }

    @Override
    public void afterFinally(NodeExecutionContext context, Object nodeResult) {
        log.info("[myfeature] post nodeId={} type={} succeeded={}",
            context.getNodeId(), context.getType(), context.isExecutionSucceeded());
    }

    @Override
    public void onExit() {
        // release resources if any
    }
}
```

#### Step 3: Register the feature

- **Internal (kernel-privileged) feature:** Add the feature module to **olo-internal-features/build.gradle** and register it in **InternalFeatures.registerInternalFeatures(...)** (e.g. `registry.registerInternal(new MyFeature())`). The worker depends on **olo-internal-features** and does not depend on the feature module directly.
- **Community (observer-only) feature:** Add the feature module to **olo-worker/build.gradle** and in the worker (or bootstrap path) call **FeatureRegistry.getInstance().registerCommunity(new MyFeature())**. Community features must not block execution; if they throw, the executor logs and continues.

No SPI is used for features; registration is explicit so the worker can control which features are included.

#### Step 4: Enable the feature in a pipeline

In the pipeline config, add your feature to **scope.features** so it runs for that pipeline:

```json
"scope": {
  "plugins": [ ... ],
  "features": [
    { "id": "debug", "displayName": "Debug" },
    { "id": "myfeature", "displayName": "My Feature" }
  ]
}
```

Optional: **contractVersion** in the feature def can be used for compatibility checks; the config validator can ensure the pipeline expects a compatible feature version.

### 2.3 Feature execution order

**Phase execution flow (per node):**

```
    PRE  →  NODE EXECUTION  →  POST_SUCCESS (on success) or POST_ERROR (on error)  →  FINALLY (always)
```

**Resolved feature order (within each phase):**  
Features are merged in this order (first → last = execution order). `featureNotRequired` excludes; **first occurrence wins** for position.

```
    Node explicit  →  Legacy postExecution  →  Node features  →  Scope + queue (-debug)  →  featureRequired
```

- **scope.features** order is preserved when added. **node.features** order is preserved.
- **Same feature in both node and scope:** The earlier source in the merge order wins. Since node.features is before scope, **node.features wins**—the feature is placed when node.features is processed; scope does not add it again.

See [architecture-and-features.md § 3.2.1](architecture-and-features.md#321-feature-execution-order-defined-contract) for the full flow diagram, merge-order details, and order-determinism contract.

### 2.4 Feature Resolution Summary

- **Scope features** – list of feature ids in `scope.features` for the pipeline; these are enabled for that pipeline.
- **Queue name** – if the task queue name ends with `-debug`, the **debug** feature is automatically added to the enabled set.
- **Node-level** – a node can have `preExecution`, `postSuccessExecution`, `postErrorExecution`, `finallyExecution`, `features`, `featureRequired`, `featureNotRequired` to attach or exclude features per node.
- Only features that are **registered** in `FeatureRegistry` and **applicable** to the node type (via `applicableNodeTypes`) are invoked.

### 2.5 Reference: Existing Features

- **olo-feature-debug** – `DebuggerFeature`: PRE_FINALLY, logs before/after every node; applicable to `*`.
- **olo-feature-quota** – `QuotaFeature`: PRE, runs on root SEQUENCE only. **Must only run on the root node and once per run**—enable via pipeline scope.features only; do not attach per node (e.g. node-level preExecution/features). Checks tenant quota from config and Redis; throws `QuotaExceededException` if exceeded.
- **olo-feature-metrics** – `MetricsFeature`: PRE_FINALLY, increments counters and records plugin execution metrics (duration, tokens, etc.); applicable to `*`.
- **olo-run-ledger** – `RunLevelLedgerFeature`, `NodeLedgerFeature`: ledger-run on root, ledger-node on every node; persist run/node records when run ledger is enabled.

---

## Quick Reference

| Goal | Plugin | Feature |
|------|--------|---------|
| **Contract** | `ExecutablePlugin` (or specific: ModelExecutor, Embedding, VectorStore, ImageGeneration) | `PreNodeCall` and/or `FinallyCall` (or PostSuccessCall, PostErrorCall, PreFinallyCall) |
| **Metadata** | None (id/contract from PluginProvider) | `@OloFeature(name, phase, applicableNodeTypes)` |
| **Discovery** | **Internal:** explicit registration in plugin module (e.g. `InternalPlugins`), **not** ServiceLoader. **Community:** ServiceLoader **per isolated JAR only** in `OLO_PLUGINS_DIR`; no system/classpath scanning. | Explicit registration in `OloWorkerApplication` |
| **Worker dependency** | Internal: via `olo-internal-plugins`. Community: JARs in `OLO_PLUGINS_DIR` only; no worker classpath dependency. | `implementation project(':olo-feature-xxx')` and `register(new MyFeature())` |
| **Config** | Pipeline scope `plugins` + execution tree node `pluginRef`, `inputMappings`, `outputMappings` | Pipeline scope `features`; optional node-level feature lists |
| **Env** | PluginProvider reads env (e.g. base URL, model); optional `isEnabled()` | Feature can read config from `NodeExecutionContext.getTenantConfigMap()` or static/env as needed |
