# Execution Tree Design — Specification

This document defines the **Execution Tree** architecture for Olo: the declarative tree of nodes that describes a pipeline’s control flow, variable flow, plugin invocations, and feature attachment. It aligns with [node-type-catalog](node-type-catalog.md), [pipeline-configuration-how-to](pipeline-configuration-how-to.md), [variable-execution-model](variable-execution-model.md), [feature-design](feature-design.md), and [architecture-and-features](architecture-and-features.md).

---

## Goals

- **Declarative pipelines** — A pipeline is a **tree of nodes** (JSON or equivalent) stored in **executionTree**. No code in the Execution Tree; node types and params define behavior.
- **Clear control flow** — Sequence, branch (IF, SWITCH), loop (ITERATOR), parallel (FORK/JOIN), and invocation (PLUGIN, PLANNER, etc.) are first-class node types.
- **Variable-centric** — Variables are declared (variableRegistry); nodes read/write via **inputMappings** and **outputMappings**. IN/INTERNAL/OUT scope and inputContract/outputContract/resultMapping define the pipeline’s data contract.
- **Feature hooks** — Every node can have pre/post feature lists; the executor runs PRE → node → POST_SUCCESS or POST_ERROR → FINALLY per node. See [feature-design](feature-design.md).
- **Plugin-agnostic** — PLUGIN nodes reference **pluginRef** from scope; the executor resolves the plugin and invokes it. No hard-coded plugin types in the Execution Tree.

---

## What Is the Execution Tree?

The **Execution Tree** is the **root node** of a pipeline’s **executionTree** config field. It is a directed tree of **nodes** (represented at runtime by **ExecutionTreeNode**):

- **Root** — Typically a **SEQUENCE** (or GROUP) whose children are the top-level steps.
- **Nodes** — Each node has: **id**, **type** (e.g. SEQUENCE, PLUGIN, IF, JOIN), optional **children**, and **params** (type-specific). Container nodes (SEQUENCE, IF, FORK, etc.) have **children**; leaf nodes (e.g. PLUGIN) have no children.
- **Execution** — The **ExecutionEngine** traverses the Execution Tree (depth-first or according to node semantics). For each node it: runs **pre** features → **executes** the node → runs **postSuccess** (on success) or **postError** (on throw) → runs **finally**. See [feature-design](feature-design.md) and [feature-ordering-diagram](feature-ordering-diagram.md).

The Execution Tree is **immutable** for a run: it comes from the compiled **execution plan** (in code: `ExecutionConfigSnapshot`) produced by the **PipelineCompiler / ExecutionTreeCompiler**. That snapshot owns a fixed `ExecutionTreeNode` graph that **must not be mutated at runtime**; the Execution Engine treats it as read‑only structure. Dynamic nodes (e.g. PLANNER‑expanded steps) are created as **separate `ExecutionTreeNode` instances** at runtime and attached only to in‑memory execution state or planner-specific structures, never by mutating the compiled snapshot. This immutability is critical for Temporal workflows (and similar engines) to guarantee determinism and replay safety.

### Terminology

Use these terms consistently:

| Term | Use |
|------|-----|
| **Execution Tree** | The concept: the declarative tree of nodes that defines a pipeline’s control and data flow. |
| **executionTree** | The pipeline config **field** that holds the root node (e.g. in JSON: `"executionTree": { "id": "root", "type": "SEQUENCE", ... }`). |
| **ExecutionTreeNode** | The **runtime class** (or equivalent) representing a single node in memory. |

Avoid mixing “tree”, “pipeline tree”, or “execution tree” when referring to the concept; prefer **Execution Tree**. Use **executionTree** when referring to the config field; use **ExecutionTreeNode** when referring to the runtime node object.

---

## Example Execution Tree

Even experts understand faster with one concrete example. Below: a pipeline that **receives a user message → classifies intent → routes to a tool → returns a response**, and the corresponding **executionTree** (root and subtree).

**Example pipeline (logical steps):**

1. Receive user message (input).
2. Classify intent (PLUGIN: intent-classifier).
3. Route to tool (SWITCH on intent).
4. Return response (output).

**Example tree (executionTree):**

```json
{
  "id": "root",
  "type": "SEQUENCE",
  "children": [
    {
      "id": "classify",
      "type": "PLUGIN",
      "params": {
        "pluginRef": "intent-classifier",
        "inputMappings": {
          "text": "userMessage"
        },
        "outputMappings": {
          "intent": "intent"
        }
      }
    },
    {
      "id": "route",
      "type": "SWITCH",
      "params": {
        "switchVariable": "intent"
      },
      "children": [
        {
          "id": "search-case",
          "type": "CASE",
          "params": { "value": "search" },
          "children": [
            {
              "id": "search-tool",
              "type": "PLUGIN",
              "params": { "pluginRef": "search-tool" }
            }
          ]
        }
      ]
    }
  ]
}
```

This helps readers immediately visualize the system: a **SEQUENCE** root, a **PLUGIN** node (classify), a **SWITCH** (route) with **CASE** children, and a **PLUGIN** (search-tool) inside one case. Variables (`userMessage`, `intent`) flow via the variable registry and mappings; the executor runs PRE → node → POST_SUCCESS/POST_ERROR → FINALLY per node.

---

## Glossary

| Term | Meaning |
|------|---------|
| **Execution Tree** | The concept: the root node (and its subtree) that defines a pipeline’s control and data flow. Stored in the pipeline’s **executionTree** config field. |
| **executionTree** | The pipeline config **field** that holds the root node (the Execution Tree). |
| **ExecutionTreeNode** | Runtime class (or equivalent) for a single node: **id**, **type**, **children** (if container), **params**, and optional feature lists (preExecution, postSuccessExecution, etc.). |
| **Node** | A single vertex in the Execution Tree; represented at runtime by **ExecutionTreeNode**. |
| **Node type** | The **type** field: SEQUENCE, PLUGIN, IF, SWITCH, CASE, ITERATOR, FORK, JOIN, PLANNER, TRY_CATCH, RETRY, etc. Determines execution semantics. See [node-type-catalog](node-type-catalog.md). |
| **VariableEngine** | Runtime component that holds the **variable map** (name → value) for the run. Seeded from workflow input (IN variables); updated by PLUGIN outputMappings; read by inputMappings and conditions. |
| **NodeExecutor** | Executes one node: resolves feature lists, runs **pre** → **dispatchExecute(node)** → **postSuccess** or **postError** → **finally**. Dispatches by node type to the appropriate handler. |
| **ExecutionEngine** | Orchestrator: given an **ExecutionConfigSnapshot** and input values, creates VariableEngine and NodeExecutor, runs the root node (which recurses to children), then applies **resultMapping** to produce the final result. |
| **Scope** | Pipeline **scope**: **plugins** (id, contractType, params) and **features** (ids). **pluginRef** in a PLUGIN node must refer to a plugin id in scope. Features are resolved per node by FeatureAttachmentResolver. |
| **Variable registry** | Pipeline **variableRegistry**: list of variables with **name**, **type**, **scope** (IN, INTERNAL, OUT). Only declared variables may be used in mappings and conditions. See [variable-execution-model](variable-execution-model.md). |

---

## Tree Structure

### Node shape (conceptual)

Every node has at least:

| Field | Description |
|-------|-------------|
| **id** | Unique within the Execution Tree (for logging, ledger, activity routing). |
| **type** | Node type (SEQUENCE, PLUGIN, IF, SWITCH, ITERATOR, FORK, JOIN, CASE, PLANNER, TRY_CATCH, RETRY, etc.). |
| **children** | For container nodes: ordered list of child nodes. Empty or absent for leaf nodes. |
| **params** | Type-specific parameters (e.g. conditionVariable for IF, switchVariable for SWITCH, pluginRef and inputMappings/outputMappings for PLUGIN, mergeStrategy for JOIN). For PLUGIN nodes, keys inside `params` (and/or on the node itself in the “modern” flat shape) must come from the plugin’s metadata in `olo-plugin.json` (the `inputs` / `outputs` schema for that plugin). The Execution Tree must not invent ad‑hoc parameter names; it binds declared plugin parameters to variables via mappings. |

Optional (feature attachment, timeouts, and execution overrides):

| Field | Description |
|-------|-------------|
| **preExecution**, **postSuccessExecution**, **postErrorExecution**, **finallyExecution** | Explicit feature name lists for this node. |
| **features** | Shorthand: feature names; resolver adds them to pre/post by phase. |
| **featureRequired**, **featureNotRequired** | Require or exclude features for this node. |
| **scheduleToStartSeconds**, **startToCloseSeconds**, **scheduleToCloseSeconds** | Per-node activity timeout overrides (when using per-node activities). |
| **executionMode** | Optional **execution mode override** for this node. When set, it overrides the pipeline’s `executionType` for this node only. Valid values are `SYNC`, `ASYNC`, and `FIRE_AND_FORGET`. See below. |

See [pipeline-configuration-how-to](pipeline-configuration-how-to.md) for the full JSON shape and pipeline-level `executionType`.

#### Node executionMode vs pipeline executionType

Execution behavior is configured at two layers:

- **Pipeline-level `executionType`** — defined on the `PipelineDefinition` and applied as the **default** for all nodes.
- **Node-level `executionMode`** — optional field on `ExecutionTreeNode` that **overrides** the pipeline default for that specific node.

Allowed values for both:

- **`SYNC`** — Node executes **synchronously** with respect to the driving workflow / activity. The worker waits for this node to finish before proceeding. In a Temporal setup, this typically means the workflow blocks until the node’s underlying activity / sub‑workflow completes and the result is available.
- **`ASYNC`** — Node is **scheduled asynchronously**: the engine records an execution handle (e.g. run id or correlation id), but does not block the main path while it completes. Temporal mapping is usually a non‑blocking workflow or activity start (returning a handle that later nodes or external consumers can use via events/queries).
- **`FIRE_AND_FORGET`** — Node is **fire‑and‑forget**: it triggers a background workflow/activity or external side effect and immediately continues without tracking completion (beyond optional logging/events). This is suitable for low‑criticality side effects (telemetry, best‑effort notifications).

This **node‑level `executionMode` is independent of plugin `ExecutionMode`** (the enum that decides whether a plugin runs as a Temporal workflow‑thread call, activity, local activity, or child workflow). Think of:

- Pipeline / node `executionType` / `executionMode` → **how the pipeline and nodes are scheduled** (blocking vs non‑blocking, request/response vs background).
- Plugin `ExecutionMode` (`WORKFLOW`, `LOCAL_ACTIVITY`, `ACTIVITY`, `CHILD_WORKFLOW`) → **how the plugin is executed inside Temporal** once the node has decided to run it.

### Container vs leaf

- **Container nodes** — Have **children** and control flow: SEQUENCE (order), IF (then/else), SWITCH (CASE children), ITERATOR (body), FORK (branches), JOIN (merge). They do not call plugins directly; they recurse into children.
- **Leaf nodes** — No children (or synthetic children created at runtime). **PLUGIN** invokes a plugin; **PLANNER** interprets a plan and runs dynamic steps; **LLM_DECISION**, **REFLECTION**, **FILL_TEMPLATE**, **SUB_PIPELINE**, **EVENT_WAIT** have type-specific semantics. See [node-type-catalog](node-type-catalog.md).

---

## Node Type Categories

Node types are grouped by purpose (see [node-type-catalog](node-type-catalog.md) for full catalog and params):

| Category | Types | Purpose |
|----------|--------|---------|
| **Control flow** | SEQUENCE, IF, SWITCH, CASE, ITERATOR, FORK, JOIN, GROUP | Order, branch, loop, parallel split/merge. |
| **Execution** | PLUGIN | Invoke a plugin (pluginRef, inputMappings, outputMappings). |
| **Planner / AI** | PLANNER, FILL_TEMPLATE, LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION | Plan interpretation, templates, LLM decisions, routing, evaluation, reflection. |
| **Enterprise** | TRY_CATCH, RETRY, SUB_PIPELINE, EVENT_WAIT | Error handling, retry, sub-pipeline call, event wait. |

**JOIN** requires a **mergeStrategy** (ALL, ANY, FIRST_WINS, LAST_WINS, REDUCE, PLUGIN, …); REDUCE/PLUGIN use **pluginRef** and mappings. **SWITCH** children must be **CASE** nodes. **PLUGIN** and **PLANNER** are the main extension points for model and tool execution.

---

## NodeType enum (overview)

`NodeType` is the enum used by the Execution Engine to dispatch behavior. The full, authoritative list lives in code and in `node-type-catalog.md`, but the most important values are:

| `NodeType`      | Description                                 |
|-----------------|---------------------------------------------|
| `SEQUENCE`      | Executes children in order.                 |
| `GROUP`         | Logical grouping of children; similar to SEQUENCE but often used for UI/organization. |
| `PLUGIN`        | Executes a plugin (`pluginRef`, mappings).  |
| `PLANNER`       | Runs a planner and generates dynamic nodes/steps. |
| `IF`            | Conditional branch; executes THEN/ELSE child based on a condition variable. |
| `SWITCH`        | Multi-way branch over a switch variable; children are `CASE` nodes. |
| `CASE`          | Branch within a SWITCH; typically has a `value` param and children. |
| `ITERATOR`      | Loop over a collection variable; runs body children per item. |
| `FORK`          | Starts child branches that can run in parallel. |
| `JOIN`          | Joins branches from a FORK; applies a `mergeStrategy`. |
| `TRY_CATCH`     | Error-handling wrapper with TRY and CATCH children. |
| `RETRY`         | Retries execution of its child(ren) according to policy. |
| `SUB_PIPELINE`  | Calls another pipeline as a sub-flow.       |
| `EVENT_WAIT`    | Waits for an external event or signal.      |
| `HUMAN_STEP`*   | (Planned) Pauses execution for human input/approval. |

The engine always dispatches on `NodeType` (`type` field in JSON); additional specializations (e.g. UI labels or planner hints) are handled via params and metadata, not by introducing new dispatch enums.

---

## Runtime stack (simplified)

Where the Execution Tree sits in the runtime:

```
Pipeline Config
      ↓
Execution Tree   (declarative program: SEQUENCE, IF, PLUGIN, SWITCH, …)
      ↓
Execution Engine (interprets the tree; NodeExecutor, ResultMapper)
      ↓
Plugins          (pluginRef → PluginRegistry; do the work)
```

Features and Variables are used by the Execution Engine at each node: **Features** run pre/post around the node; **Variables** (VariableEngine) flow data via inputMappings, outputMappings, resultMapping. See [architecture-and-features](architecture-and-features.md) for the full Olo Runtime Stack diagram.

---

## Execution context

Each node execution receives a **runtime context** that provides everything needed to execute the node and resolve plugins, features, and variables. Defining it here ensures consistent implementation across the executor and node handlers.

| Field | Description |
|-------|-------------|
| **tenantId** | Current tenant for the run. |
| **runId** | Unique run identifier (e.g. workflow run id). |
| **variableEngine** | Variable store for this run (name → value). Read/write via inputMappings and outputMappings. |
| **pluginRegistry** | Plugin resolver (e.g. **PluginRegistry.get(pluginId)**). Used to resolve **pluginRef** for PLUGIN nodes. |
| **featureRegistry** | Feature registry; used with **FeatureAttachmentResolver** to resolve pre/post feature lists for the node. |
| **tenantConfig** | Tenant configuration (tenantConfigMap). Passed to plugins and available for feature/guard logic. |
| **snapshotVersionId** | Pipeline snapshot id (ExecutionConfigSnapshot). Identifies the exact config version used for this run. |

Additional context may include: **queueName**, **nodeId** (current node), **parent context** (for nested runs), and **execution options** (timeouts, feature overrides). The executor creates this context once per run (or per activity) and passes it into **NodeExecutor** and feature hooks (e.g. **NodeExecutionContext** in [feature-design](feature-design.md)).

---

## Variable Model

- **variableRegistry (pipeline scope)** — Variables are declared once per pipeline in `variableRegistry` (name, type, scope: IN, INTERNAL, OUT). This registry defines the **pipeline‑level variable scope**: a single logical variable map shared by all nodes in the Execution Tree for the duration of a run.
- **Lifecycle and mutability** — At the start of a run, the engine creates a **VariableEngine** for the pipeline: IN variables are seeded from workflow input, INTERNAL variables are initialized to null, and OUT variables start unset. As nodes execute, PLUGIN (and other) nodes **mutate** this shared variable map via inputMappings/outputMappings; later nodes see the updated values. Variables are therefore **mutable over time**, but **never re‑scoped per node**: nodes observe and update the same pipeline‑level map.
- **Scope levels in practice** — While there is only one concrete VariableEngine instance per run, you can think of scopes as:
  - **Global / tenant scope**: configuration and connection definitions (outside the Execution Tree; read-only from the tree’s perspective).
  - **Pipeline scope**: the `variableRegistry` + variable map for a specific pipeline execution (read/write during the run).
  - **Node scope**: each node receives a view of the same pipeline variable map and may read/write variables via mappings, but nodes do not introduce new variable names.
  - **Planner / dynamic scopes**: planner steps and dynamic nodes still target the same declared variables; they may generate node JSON at runtime but must write into the existing pipeline variable space.
- **Contract rules** — IN variables must align with `inputContract`; OUT variables referenced in `resultMapping` must be assigned before completion; INTERNAL variables are for intermediate state only. All variable reads/writes are type‑checked against the registry and plugin contract.

See [variable-execution-model](variable-execution-model.md) for the full contract and validation rules.

---

## Variable scope (where variables are visible)

Variables declared in `variableRegistry` form a single **pipeline-wide scope** and are visible to all parts of the Execution Tree that work with data:

- **Plugin input mappings** — `inputMappings` on PLUGIN nodes read values from the variable store; `variable` names must exist in `variableRegistry`.
- **Plugin output mappings** — `outputMappings` write plugin results back into variables declared in `variableRegistry` (typically INTERNAL or OUT).
- **Condition expressions** — Control-flow nodes (e.g. IF, SWITCH, ITERATOR) reference variables (e.g. `conditionVariable`, `switchVariable`, `collectionVariable`) that must be declared in `variableRegistry`.
- **Planner steps / dynamic nodes** — Planner-generated nodes and dynamic steps use the same variable names for their `inputMappings`/`outputMappings`; they do not introduce new variable scopes.

During execution, **variables are updated in-place** in the runtime `VariableEngine` as nodes write outputs. Subsequent nodes (including planner steps, conditions, and plugins) always see the latest values for any variables they reference, subject to the IN/INTERNAL/OUT scope and type rules defined in the variable model.

---

## Parameter sources (`params` vs `inputMappings`)

For PLUGIN nodes, each **plugin parameter** can be supplied from two distinct sources, and the distinction is important for validation and runtime behavior:

- **Variable mapping (`inputMappings`)** — The parameter value is read from the **variable map** at execution time.
  - Example: `prompt` ← variable `userQuery`.
  - JSON:
    ```json
    "inputMappings": [
      { "pluginParameter": "prompt", "variable": "userQuery" }
    ]
    ```

- **Literal config (`params`)** — The parameter value is taken **directly from node configuration** (usually a JSON literal), independent of variables.
  - Example: `temperature` ← literal `0.2`.
  - JSON:
    ```json
    "params": {
      "temperature": 0.2
    }
    ```

Validation uses this distinction:

- `inputMappings.pluginParameter` names must exist in the plugin’s **input schema** (from `olo-plugin.json`), and the mapped variable must exist in `variableRegistry` with a compatible type.
- `params` keys must also correspond to declared plugin input parameters and have values that are type-compatible with those parameters’ declared types.

At runtime, the executor first resolves variable-backed parameters via `inputMappings` and then merges in literal `params` (with a clearly defined precedence policy), so every plugin input parameter ultimately has a value from either the variable map, a literal, or a default defined by the plugin.

---

## Possible simplification: separate plugin invocation model

Today, `ExecutionTreeNode` carries both **execution-tree structure** (id, type, children) and **plugin invocation details** (`pluginRef`, `inputMappings`, `outputMappings`, `params`, `connections`) for PLUGIN nodes. A future architectural simplification is to split these concerns:

- **`ExecutionTreeNode`** — purely structural:
  - `id`, `type`, `children`, feature lists, timeouts, executionMode, etc.
  - For PLUGIN nodes, it would hold only enough information to say “this node invokes a plugin”.

- **`PluginInvocation` (conceptual model)** — plugin-specific invocation contract:
  - `pluginRef`
  - `inputMappings`
  - `outputMappings`
  - `params`
  - `connections`

The Execution Engine would then:

- Dispatch on `ExecutionTreeNode.type` as today.
- For a PLUGIN node, delegate to a separate **plugin invocation layer** that works with `PluginInvocation` and plugin descriptors (`olo-plugin.json`).

Benefits of this separation:

- **Simpler execution engine** — core traversal and control-flow logic lives entirely in `ExecutionTreeNode`; plugin invocation concerns move to a dedicated component.
- **Independent evolution of plugin contracts** — changes to plugin params, capabilities, connection handling, or planner integration can evolve in `PluginInvocation` and the plugin runtime without impacting the structural Execution Tree model.
- **Cleaner planner integration** — planners can think in terms of “build a `PluginInvocation`” while reusing a stable `ExecutionTreeNode` structure, making dynamic tool routing easier to reason about.

This document describes the current, combined model, but new designs should consider this split when evolving the plugin runtime and planner subsystems.

---

## Execution Flow

### Execution flow diagram (core runtime path)

What happens when a pipeline runs—from user request to external service:

```
User / API
    │
    ▼
Pipeline Run Request
    │
    ▼
Temporal Workflow
    │
    ▼
Execution Engine
    │
    ▼
Execution Tree
(SEQUENCE / IF / PLUGIN / FORK / JOIN)
    │
    ▼
Node Executor
    │
    ▼
Plugin Executor
    │
    ▼
External Service
(OpenAI / DB / Tool)
```

This diagram explains the core runtime path: the request hits Temporal, which drives the Execution Engine; the engine interprets the Execution Tree and, for each node, the Node Executor runs features and dispatches to the Plugin Executor (or other type handler), which calls the external service.

### High-level

1. **Activity** receives a run request (e.g. **runExecutionTree(queueName, workflowInputJson)** or per-node **executeNode(...)** when the plan is linear).
2. **Tenant** and **config** — Resolve tenantId, load **ExecutionConfigSnapshot** (tenantId, queueName, pipeline config deep copy, snapshotVersionId). No global config reads during the run.
3. **ExecutionEngine.run(snapshot, inputValues, pluginExecutor, tenantConfigMap)** — Build **VariableEngine** (seeded with inputValues), **NodeExecutor** (tenantId, tenantConfigMap, feature registry), run the **root node**.
4. **Per node** — NodeExecutor: resolve **ResolvedPrePost** (pre, postSuccess, postError, finally) for this node → **runPre** → **dispatchExecute(node)** (recurse for containers; invoke plugin or type-specific logic for leaves) → on success **runPostSuccess**, on throw **runPostError** → **runFinally** (always).
5. **Result** — After root completes, **ResultMapper.apply(variableMap, resultMapping)** produces the final result (OUT variables → outputContract parameters).

### Linear vs non-linear

- **Non-linear tree** (IF, SWITCH, FORK, etc.) — Workflow calls **runExecutionTree** once; one activity runs the full tree via **ExecutionEngine.run(...)**.
- **Linear tree** (only SEQUENCE, GROUP, and leaves) — Workflow can call **getExecutionPlan** then **executeNode** per leaf (one Temporal activity per node); **ExecutionConfigSnapshot** and **runId** are shared so the run is still one logical execution. See [architecture-and-features](architecture-and-features.md) and [run-ledger-schema](run-ledger-schema.md).

Dynamic nodes produced by planners (e.g. TOOL_ROUTER or PLANNER nodes) are treated exactly like static nodes once materialized into `ExecutionTreeNode`s: they still execute under the pipeline’s **scope** and **safety constraints**. In particular, **`pluginRestrictions`** and **`featureRestrictions`** on the pipeline definition continue to apply; the executor must not run planner-generated nodes that reference plugins or features outside the allowed sets. This is a **hard safety guarantee**: planners can propose nodes, but the Execution Engine enforces the configured restriction lists so that planner output can never “escape” the allowed plugin/feature surface area.

### Diagram (per node)

```
    Resolve pre/post lists (FeatureAttachmentResolver)
                    │
                    ▼
    ┌─────────────────────────────────────┐
    │  PRE (feature hooks)                 │
    └─────────────────────────────────────┘
                    │
                    ▼
    ┌─────────────────────────────────────┐
    │  dispatchExecute(node)               │  ← Recurse (container) or invoke plugin / type handler (leaf)
    └─────────────────────────────────────┘
                    │
         ┌─────────┴─────────┐
         ▼                   ▼
    POST_SUCCESS        POST_ERROR
         └─────────┬─────────┘
                   ▼
    ┌─────────────────────────────────────┐
    │  FINALLY (feature hooks)             │
    └─────────────────────────────────────┘
```

---

## Scope and Resolution

- **Scope** — Each pipeline has **scope.plugins** and **scope.features**. **pluginRef** in a PLUGIN (or JOIN with REDUCE/PLUGIN) must be a plugin **id** present in scope. The executor resolves the plugin via **PluginRegistry.get(tenantId, pluginId)** (or equivalent).
- **Features** — **FeatureAttachmentResolver** merges node-level lists (preExecution, postSuccessExecution, etc., and **features**), scope.features, queue-based rules (e.g. `-debug` → debug), and featureRequired/featureNotRequired to produce the four lists (pre, postSuccess, postError, finally) for the node. See [feature-design](feature-design.md).

Pipeline config can also define **pluginRestrictions** and **featureRestrictions** at the root to allow only certain plugin/feature ids across pipelines.

### Secrets and connections

Secrets are not carried directly on Execution Tree nodes; they are resolved through **connections** (preferred) or through **logical secret references** inside params (advanced).

- **Via connections (recommended path)**  
  - Node JSON uses the **`connections`** map (see §Execution Tree JSON → `ExecutionTreeNode`) to bind **logical roles** (e.g. `"model"`, `"vectorStore"`, `"storage"`) to **connection names** (e.g. `"gpt4-prod"`, `"pgvector-main"`).  
  - Each connection definition lives in the tenant’s configuration / Connection Manager and may contain **logical secret references** (e.g. `${secret:tenant:openai-api-key}`, `${secret:system:ollama-api-key}`) instead of raw secrets.  
  - At runtime the **ConnectionRuntimeManager** (or future **ResourceRuntimeManager**) and **SecretResolver** collaborate: the connection config is loaded, secret placeholders are resolved, and a concrete client/runtime is created. Plugins normally access secrets only through these resolved runtimes, never via raw secret values on the node.

- **Via logical secret refs inside params (advanced/supplementary)**  
  - In more advanced scenarios, node `params` may include string values containing `${secret:...}` placeholders (e.g. a header template or inline credential for a legacy integration).  
  - Before plugin execution, a configuration interpolation step (e.g. `ConfigInterpolator`) walks the params map, detects `${secret:scope:key}` patterns, and asks the **SecretResolver** to resolve them for the current tenant and run.  
  - This keeps the Execution Tree JSON free of raw secrets while still allowing flexible secret usage when a connection abstraction is not yet available. The preferred long‑term pattern remains **connection‑centric** secret resolution.

#### Connection resolution timing

Connections on nodes are always specified as **logical names in JSON** and are resolved to concrete runtimes **at execution time**, not during compilation:

- **Config time (JSON / ExecutionTreeNode)** – The `connections` map holds logical names only, e.g.  
  ```json
  "connections": {
    "model": "gpt4-prod"
  }
  ```  
  No SDK clients or network resources are created during `PipelineCompiler`/`ExecutionTreeCompiler`; the snapshot stays purely declarative.

- **Runtime resolution** – When a node executes and needs a connection for a given role (e.g. `"model"`), the executor asks the **ResourceRuntimeManager** / **ConnectionRuntimeManager** to resolve the name for the current tenant and run:  
  ```java
  ResourceRuntime modelRuntime =
      resourceRuntimeManager.resolve("gpt4-prod", tenantId, runId);
  ```  
  That manager loads the connection config, resolves secrets via `SecretResolver`, applies caching and rate limiting, and returns a concrete client/runtime object for the plugin to use.

This separation keeps the Execution Tree and its snapshot **purely logical**, while all connection/materialization concerns live in the runtime resource layer.

---

## Integration Points

| System | How it connects to the Execution Tree |
|--------|--------------------------------------|
| **Plugins** | **PLUGIN** node: pluginRef, inputMappings, outputMappings. Executor resolves plugin from PluginRegistry and calls **execute(inputs, tenantConfig)**. See [plugin-design](plugin-design.md). |
| **Features** | Every node has resolved pre/post feature lists. Executor runs pre → node → postSuccess/postError → finally. See [feature-design](feature-design.md). |
| **Connection Manager (design)** | When implemented, pipelines may reference **connections** by name (e.g. ctx.model("openai-prod")); resolution goes through ConnectionRuntimeManager. Tree nodes may still use pluginRef for execution-tree plugins; connection-based invocation is a separate path. See [connection-manager-design](connection-manager-design.md). |
| **Run ledger** | When OLO_RUN_LEDGER=true, RunLevelLedgerFeature and NodeLedgerFeature (and optional ExecutionEventsFeature) run as features; they record run and per-node data. RunId comes from snapshot or plan. See [run-ledger-schema](run-ledger-schema.md), [execution-events](execution-events.md). |
| **Variables** | VariableEngine is created once per run; all nodes read/write the same variable map. IN/INTERNAL/OUT and resultMapping define the pipeline’s data contract. See [variable-execution-model](variable-execution-model.md). |

---

## Module Layout

```
olo-worker-execution-tree    # ExecutionTreeNode, NodeType, PipelineConfiguration, PipelineDefinition,
                             # ExecutionTreeConfig, scope, variableRegistry, executionTree root;
                             # ConfigurationLoader, GlobalConfigurationContext
olo-worker-execution-context # LocalContext, ExecutionConfigSnapshot (immutable snapshot for run)
olo-worker-features          # FeatureAttachmentResolver, NodeExecutionContext, ResolvedPrePost
olo-worker                   # ExecutionEngine, NodeExecutor, PluginInvoker, ResultMapper;
                             # dispatch by node type, runPre/runPostSuccess/runPostError/runFinally
```

Execution Tree **config** (executionTree, ExecutionTreeNode shape) lives in olo-worker-execution-tree; **execution** (ExecutionEngine, NodeExecutor) lives in olo-worker. Feature resolution lives in olo-worker-features.

---

## Summary

| Aspect | Design |
|--------|--------|
| **Execution Tree** | Root node (e.g. SEQUENCE) in **executionTree** config field; each node (ExecutionTreeNode) has id, **type** (enum `NodeType` – PLUGIN, SEQUENCE, IF, etc.), children (if container), params, optional `pluginRef`, input/output mappings, optional **`connections`** map, and optional `executionMode` override. Older configs may include a redundant `nodeType` string, but it is ignored by the engine; new JSON should omit it and rely solely on `type`. |
| **Node types** | Control flow (SEQUENCE, IF, SWITCH, ITERATOR, FORK, JOIN), PLUGIN, PLANNER, TRY_CATCH, RETRY, SUB_PIPELINE, etc. See [node-type-catalog](node-type-catalog.md). |
| **Variables** | variableRegistry (IN, INTERNAL, OUT); VariableEngine holds the map; inputMappings/outputMappings and resultMapping define data flow. See [variable-execution-model](variable-execution-model.md). |
| **Execution** | ExecutionEngine.run(snapshot, ...) → NodeExecutor per node: pre → execute → postSuccess/postError → finally. |
| **Scope** | scope.plugins, scope.features; pluginRef from scope; features resolved by FeatureAttachmentResolver. |
| **Execution context** | Per-run context: tenantId, runId, variableEngine, pluginRegistry, featureRegistry, tenantConfig, snapshotVersionId. See §Execution context. |
| **Config** | Pipeline definition (name, inputContract, variableRegistry, scope, **executionTree**, outputContract, resultMapping). Immutable snapshot per run. |

The Execution Tree is the **declarative core** of a pipeline: it defines what runs, in what order, with which variables and plugins, and with which feature hooks. For the full node catalog and params, see [node-type-catalog](node-type-catalog.md). For pipeline JSON structure and loading, see [pipeline-configuration-how-to](pipeline-configuration-how-to.md). For feature phases and order, see [feature-design](feature-design.md) and [feature-ordering-diagram](feature-ordering-diagram.md).
