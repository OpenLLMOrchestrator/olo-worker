# Input JSON Format and Deserialization Structure

This document describes the **JSON formats** Olo accepts for pipelines and dynamic nodes, and how they map to the current Java model (deserialization structures). It complements `pipeline-configuration-how-to.md` and `execution-tree-design.md` by focusing on the **wire format ↔ Java types** relationship.

High-level flow from JSON to runtime:

```text
                Pipeline JSON
                     │
                     ▼
           ExecutionTreeConfig.fromJson
                     │
                     ▼
             PipelineConfiguration
                     │
                     ▼
               PipelineValidator
                     │
                     ▼
               PipelineCompiler
                     │
                     ▼
               ExecutionPlan
          (ExecutionConfigSnapshot)
                     │
                     ▼
               Execution Engine
         ┌────────────┼────────────┐
         ▼            ▼            ▼
     Plugins       Features     Variables
```

The compiled, immutable result is the **Execution Plan**: the fixed `ExecutionTreeNode` graph plus resolved defaults and metadata. In code it is currently represented as **`ExecutionConfigSnapshot`**; see **Naming the compiled result** below for alternative names and why *ExecutionPlan* is used in the diagram.

---

## 1. Pipeline Configuration JSON → `PipelineConfiguration`

Top-level file (e.g. `sample-pipeline-configuration.json`) is parsed into `PipelineConfiguration` from `olo-worker-execution-tree`.

```java
import com.olo.executiontree.ExecutionTreeConfig;
import com.olo.executiontree.config.PipelineConfiguration;

String json = Files.readString(Path.of("docs/sample-pipeline-configuration.json"));
PipelineConfiguration config = ExecutionTreeConfig.fromJson(json);
```

### 1.2 Validation and compilation (PipelineValidator / PipelineCompiler)

Deserialization is only the first step. After JSON is parsed into Java objects, the pipeline loader should run through an explicit **validation + compilation pipeline** before the configuration is accepted for execution:

```text
Pipeline JSON
    ↓
ExecutionTreeConfig.fromJson()
    ↓
PipelineConfiguration (DTO)
    ↓
PipelineValidator
    ↓
PipelineCompiler (or ExecutionTreeCompiler)
    ↓
ExecutionPlan (ExecutionConfigSnapshot, immutable)
    ↓
Execution Engine
```

Conceptually:

- **PipelineValidator** is responsible for **semantic checks** on the DTOs:
  - **Schema / shape checks** – required fields present (e.g. `id`, `type`, `executionTree`, `variableRegistry`), node types known, enums valid, value types (`STRING` / `NUMBER` / …) recognized.
  - **Plugin validation** – for every PLUGIN node, `pluginRef` must exist in the pipeline’s `scope.plugins` and in the loaded plugin descriptors (`olo-plugin.json`); `inputMappings`/`outputMappings` must only reference parameters declared in the plugin’s `inputs` / `outputs` schema.
  - **Variable validation** – all variables referenced in mappings and conditions must exist in `variableRegistry`, with types compatible with plugin parameter types and value types; OUT variables used in `resultMapping` must be declared and writable.
  - **Feature / restriction checks** – feature names must exist in scope; `pluginRestrictions` / `featureRestrictions` must not be violated by the execution tree or dynamic node specs.

- **PipelineCompiler** (or `ExecutionTreeCompiler`) is responsible for turning a validated `PipelineConfiguration` into an **immutable, execution-ready representation**, e.g. `ExecutionConfigSnapshot`:
  - Resolve / inline defaults (timeouts, executionType, feature defaults).
  - Normalize node shapes and connections map.
  - Precompute any lookup tables the Execution Engine needs (e.g. node id → timeouts, id → executionMode, id → feature lists).
  - Freeze the resulting structure so that execution code treats it as read‑only.

Only after both `PipelineValidator` and `PipelineCompiler` have completed successfully should the config be considered a valid execution plan for use by the Execution Engine.

#### Naming the compiled result

Contributors may ask: *snapshot of what?* The compiled output is an **immutable, execution-ready plan**: the fixed Execution Tree plus resolved timeouts, execution mode, and feature defaults. In the codebase it is currently the type **`ExecutionConfigSnapshot`**. For a clearer mental model (and for OSS familiarity), these names are equivalent in concept:

| Name | Usage |
|------|--------|
| **ExecutionPlan** | Common in workflow systems (Temporal, Airflow, etc.). Reads well: *PipelineCompiler → ExecutionPlan → Execution Engine*. Recommended as the conceptual name in docs and diagrams. |
| **CompiledPipeline** | Emphasizes “compiled from config”; also clear. |
| **ExecutionConfigSnapshot** | Current Java type name. Emphasizes immutability and that it is a point-in-time snapshot of config for a run. |

The diagram at the top uses **ExecutionPlan** so the flow is instantly readable; the runtime type remains `ExecutionConfigSnapshot` unless the project adopts a rename.

### 1.1 Root JSON structure

Key fields (see `pipeline-configuration-how-to.md` for full table):

```json
{
  "version": "1.0",
  "executionDefaults": { ... },
  "pluginRestrictions": ["GPT4_EXECUTOR"],
  "featureRestrictions": ["debug", "metrics"],
  "pipelines": {
    "ai-pipeline": {
      "name": "ai-pipeline",
      "workflowId": "AI_PIPELINE",
      "inputContract": { ... },
      "variableRegistry": [ ... ],
      "scope": { ... },
      "executionTree": { ... },
      "outputContract": { ... },
      "resultMapping": [ ... ],
      "executionType": "SYNC"
    }
  }
}
```

Deserialized into:

- `PipelineConfiguration`
  - `String version`
  - `ExecutionDefaults executionDefaults`
  - `List<String> pluginRestrictions`
  - `List<String> featureRestrictions`
  - `Map<String, PipelineDefinition> pipelines`

Each `PipelineDefinition` contains:

- `String name`
- `String workflowId`
- `InputContract inputContract`
- `List<VariableDef> variableRegistry`
- `Scope scope` (plugins/features available)
- `ExecutionTreeNode executionTree` (root node)
- `OutputContract outputContract`
- `List<ResultMapping> resultMapping`
- `String executionType` (`"SYNC"` / `"ASYNC"`)

High-level relationship between these types:

```text
PipelineConfiguration
   │
   └── pipelines : Map<String, PipelineDefinition>

PipelineDefinition
   │
   ├── inputContract
   ├── variableRegistry
   ├── scope
   ├── executionTree : ExecutionTreeNode
   └── resultMapping

ExecutionTreeNode
   │
   ├── type
   ├── children
   ├── pluginRef (for PLUGIN nodes)
   ├── inputMappings / outputMappings
   └── params / connections
```

---

## 2. Value Types and Variable Mapping

Across input contracts, variable registry, plugin params, connection schemas and UI forms, Olo uses a **single global value type system**:

- `STRING`
- `NUMBER`
- `BOOLEAN`
- `JSON`
- `ARRAY`
- `OBJECT`

These types appear in:

- `inputContract.parameters[].type`
- `variableRegistry[].type`
- Plugin `inputs` / `outputs` in `olo-plugin.json`
- `ConnectionSchema.fields[].type`

Example JSON:

```json
{
  "name": "prompt",
  "type": "STRING",
  "required": true
}
```

This is deserialized into the appropriate parameter/variable/config field DTO and participates in validation and UI generation.

---

## 3. Execution Tree JSON → `ExecutionTreeNode`

The `executionTree` object in pipeline config is parsed into an `ExecutionTreeNode` graph.

### 3.1 Node JSON structure

Example PLUGIN node (modern shape — no `nodeType` duplication):

```json
{
  "id": "node-1",
  "type": "PLUGIN",
  "pluginRef": "GPT4_EXECUTOR",
  "inputMappings": [
    { "pluginParameter": "prompt", "variable": "userQuery" }
  ],
  "outputMappings": [
    { "pluginParameter": "responseText", "variable": "finalAnswer" }
  ],
  "connections": {
    "model": "gpt4-prod",
    "vectorStore": "pinecone-main"
  },
  "params": {
    "promptTemplate": "Answer the question: {{question}}",
    "authorizationHeader": "Bearer ${secret:tenant:openai-api-key}"
  },
  "features": ["metrics"],
  "preExecution": [],
  "postExecution": [],
  "postSuccessExecution": [],
  "postErrorExecution": [],
  "finallyExecution": [],
  "featureRequired": [],
  "featureNotRequired": [],
  "scheduleToStartSeconds": null,
  "startToCloseSeconds": null,
  "scheduleToCloseSeconds": null,
  "executionMode": null
}
```

#### 3.1.1 `type` (and legacy `nodeType`)

- `type` → enum `NodeType` in Java. This is what the **Execution Engine** dispatches on (e.g. `NodeType.PLUGIN`, `NodeType.SEQUENCE`, `NodeType.IF`, etc.) and is the **only required discriminator** for node behavior.
- `nodeType` → **legacy / auxiliary** string label that historically duplicated `type`. It is **not** used for dispatch and **should be omitted** in all new pipeline JSON. The engine will continue to accept it for backward compatibility, but new tooling and configs should rely solely on `type`.

#### 3.1.2 Node types

The **`type`** field of `ExecutionTreeNode` corresponds to the **`NodeType`** enum used by the Execution Engine. Typical node types include:

| NodeType    | Description                          |
|-------------|--------------------------------------|
| `PLUGIN`    | Executes a plugin.                   |
| `SEQUENCE`  | Executes children sequentially.     |
| `FORK`      | Executes children concurrently (parallel). |
| `IF`        | Conditional branching.               |
| `ITERATOR`  | Iterative execution (loop over a collection). |
| `SWITCH` / `CASE` | Multi-way branching.          |
| `JOIN`      | Merges parallel branches.           |
| `PLANNER`   | Generates dynamic nodes.             |
| `HUMAN_STEP`| (Planned) Pauses for external/human input. |

The full catalog and per-type parameters are in [node-type-catalog](node-type-catalog.md) and [execution-tree-design](execution-tree-design.md#nodetype-enum-overview).

#### 3.1.3 Timeouts and executionMode override

The following optional fields control **per-node timeouts** and **execution behavior**:

- `scheduleToStartSeconds`, `startToCloseSeconds`, `scheduleToCloseSeconds` — Per-node activity timeout overrides (when using per-node activities). If omitted, the engine falls back to the resolved defaults from `executionDefaults.activity.defaultTimeouts` via the configuration loader (see `pipeline-configuration-how-to.md`).
- `executionMode` — Optional **execution mode override** for this node. When `null` or absent, the node inherits the pipeline’s `executionType`. When set, it overrides the pipeline default for this node only. Valid values are:
  - `"SYNC"` — node runs synchronously; the driving workflow/activity waits for completion.
  - `"ASYNC"` — node is started asynchronously; the engine can continue while this node completes in the background, typically tracked via an execution handle (e.g. Temporal run id).
  - `"FIRE_AND_FORGET"` — node triggers a background action and does not track completion (best‑effort side effect).

This field is **separate from** the plugin‑level `ExecutionMode` enum (`WORKFLOW`, `LOCAL_ACTIVITY`, `ACTIVITY`, `CHILD_WORKFLOW`), which controls *how* the plugin is executed within Temporal once the node decides to run it.

#### 3.1.4 Plugin parameter sources

Plugin parameters may be supplied in two ways:

| Source           | Description                                              |
|------------------|----------------------------------------------------------|
| **inputMappings**| Maps **runtime variables** to plugin parameters.         |
| **params**       | **Static configuration values** defined in the pipeline. |

Example:

```json
"inputMappings": [
  { "pluginParameter": "prompt", "variable": "userQuery" }
],
"params": {
  "temperature": 0.2
}
```

In this example:

- **prompt** ← runtime variable `userQuery` (value from the variable store at execution time).
- **temperature** ← static config `0.2` (fixed in the pipeline JSON).

This distinction matters for:

- **Validation** — Variables in `inputMappings` must exist in `variableRegistry`; keys in `params` must match the plugin’s declared input schema.
- **UI forms** — Editors can show variable pickers for mapped inputs and literal fields for `params`.
- **Planner integration** — Planners can fill `inputMappings` from plan variables and leave fixed options in `params`.

Deserialized into:

```java
public final class ExecutionTreeNode {
    private final String id;
    private final String displayName;
    private final NodeType type;
    private final List<ExecutionTreeNode> children;
    private final String nodeType;
    private final String pluginRef;
    private final List<ParameterMapping> inputMappings;
    private final List<ParameterMapping> outputMappings;
    private final List<String> features;
    private final List<String> preExecution;
    private final List<String> postExecution;
    private final List<String> postSuccessExecution;
    private final List<String> postErrorExecution;
    private final List<String> finallyExecution;
    private final List<String> featureRequired;
    private final List<String> featureNotRequired;
    private final Map<String, Object> params;
    // Optional: logical connection roles → connection names (resolved via Connection Manager / ResourceRuntimeManager)
    private final Map<String, String> connections;
    private final Integer scheduleToStartSeconds;
    private final Integer startToCloseSeconds;
    private final Integer scheduleToCloseSeconds;
    private final String executionMode; // optional override; maps to ExecutionMode
    ...
}
```

`inputMappings` / `outputMappings` map directly into `ParameterMapping` objects:

```java
public final class ParameterMapping {
    private final String pluginParameter;
    private final String variable;
    ...
}
```

For PLUGIN nodes, the allowed **`pluginParameter` names** in `inputMappings` / `outputMappings`, as well as any plugin-specific keys inside `params`, are **not arbitrary strings**: they are derived from the plugin’s descriptor in `olo-plugin.json` (the `inputs` / `outputs` schema for that plugin’s id). At authoring time, pipeline configs and planner outputs should only use parameter names that exist in the corresponding plugin metadata; the runtime can validate mappings against the descriptor to catch typos or unsupported fields.

#### 3.1.5 Connection resolution

The `connections` map defines **logical roles** used by the plugin and their **named connections**:

```json
"connections": {
  "model": "gpt4-prod"
}
```

At execution time, the engine resolves each connection name through the **Connection Manager / ResourceRuntimeManager**, which is responsible for loading the connection configuration, resolving any secrets, and providing the concrete runtime client or resource handle required by the plugin.

---

## 4. Dynamic Node JSON → `ExecutionTreeNode` / `DynamicNodeSpec`

Some flows (e.g. planners) use **dynamic node JSON** at runtime. This JSON is parsed into `ExecutionTreeNode` or `DynamicNodeSpec`.

### 4.1 Dynamic node format (planner)

Example step:

```json
{
  "nodeId": "step-0",
  "activityType": "PLUGIN:RESEARCH_TOOL",
  "pluginRef": "RESEARCH_TOOL",
  "displayName": "step-0-RESEARCH_TOOL",
  "features": [],
  "inputMappings": [
    { "pluginParameter": "prompt", "variable": "__planner_step_0_prompt" }
  ],
  "outputMappings": [
    { "pluginParameter": "responseText", "variable": "__planner_step_0_response" }
  ]
}
```

Deserialization utility (`NodeExecutionStepUtils.nodeFromDynamicStep`) maps this into:

- `ExecutionTreeNode` with:
  - `id` ← `nodeId`
  - `displayName` ← `displayName` or default `"step"`
  - `pluginRef` ← `pluginRef`
  - `inputMappings` / `outputMappings` built from the arrays
  - `features` built from the list
  - Other lists/params initialized empty/null.

**Naming note:** static nodes in pipeline JSON use the field name `id`, whereas current planner output still uses `nodeId`. For future formats and new APIs, prefer **`id`** for both static and dynamic nodes so that dynamic JSON is structurally identical to static JSON wherever possible. Existing code still expects `nodeId` for planner output; treat this as a legacy naming quirk when reading/writing planner results.

#### 4.1.1 Proposed future dynamic shape (cleaner contract)

The current dynamic format mixes **execution-tree concerns** (`activityType`, `nodeId`) with **plugin contract details** (`pluginRef`, mappings). A cleaner, future-friendly shape separates these concerns and aligns more directly with plugin schemas:

```json
{
  "id": "step-0",
  "type": "PLUGIN",
  "plugin": {
    "ref": "RESEARCH_TOOL",
    "params": {}
  },
  "inputs": [
    { "param": "prompt", "var": "__planner_step_0_prompt" }
  ],
  "outputs": [
    { "param": "responseText", "var": "__planner_step_0_response" }
  ]
}
```

Benefits of this structure:

- **Plugin structure isolated** — All plugin-specific information (id, params, mappings) lives under the `plugin` / `inputs` / `outputs` fields, making the execution engine’s core node shape (`id`, `type`, children) easier to reason about.
- **Easier UI rendering** — UIs can render a “plugin call” card using the nested `plugin` object and mapping arrays directly, without reverse‑engineering from generic `params` maps.
- **Stronger validation** — A dedicated `plugin` block aligns cleanly with plugin descriptor schemas (`olo-plugin.json`), enabling stricter compile‑time / runtime validation of parameters and mappings.
- **Planner alignment** — Planners can think in terms of “call plugin X with inputs/outputs” without worrying about low‑level execution engine fields like `activityType`. The engine can still translate this structure into a canonical `ExecutionTreeNode` internally.

This shape is **not yet the wire contract** and should be treated as a **proposed evolution** for future planner APIs and dynamic node formats; existing code continues to expect the legacy `nodeId` / `pluginRef` format described above.

### 4.2 Planner-generated nodes (`JsonPlannerNodeFactory` / `PlannerCreatorSteps`)

Planner modules (`olo-planner-a`) convert planner step info into:

- `NodeSpec` (configuration-level description).
- `ExecutionTreeNode` for immediate execution.

Example mapping:

```java
List<ParameterMapping> inputMappings =
    List.of(new ParameterMapping("prompt", vars.promptVar()));
List<ParameterMapping> outputMappings =
    List.of(new ParameterMapping("responseText", vars.responseVar()));

ExecutionTreeNode node = new ExecutionTreeNode(
    UUID.randomUUID().toString(),
    "step-" + index + "-" + info.toolId(),
    NodeType.PLUGIN,
    List.of(),
    "PLUGIN",
    info.toolId(),
    inputMappings,
    outputMappings,
    ...
    null // executionMode override
);
```

This keeps the dynamic JSON format aligned with the static `ExecutionTreeNode` shape.

Even though these nodes are generated at runtime, they are still executed under the same **safety constraints** as static nodes: **`pluginRestrictions`** and **`featureRestrictions`** from the pipeline configuration still apply, and the executor must reject or ignore planner-generated nodes that reference plugins or features outside the allowed sets. Planners cannot “escape” the pipeline’s configured safety envelope.

---

## 5. Summary

- **Pipeline configuration JSON** ↔ `PipelineConfiguration` / `PipelineDefinition`.
- **Execution tree JSON** (`executionTree`) ↔ `ExecutionTreeNode` graph.
- **Dynamic node JSON** ↔ `ExecutionTreeNode` / `DynamicNodeSpec` via helper utilities.
- **Olo value types** (`STRING`, `NUMBER`, `BOOLEAN`, `JSON`, `ARRAY`, `OBJECT`) are used consistently across:
  - Input contracts
  - Variable registry
  - Plugin input/output schemas
  - Connection schemas
  - UI forms

This document should be used alongside `pipeline-configuration-how-to.md` and `execution-tree-design.md` when adding new node types, dynamic steps, or input formats, to ensure JSON ↔ Java mappings stay consistent.

