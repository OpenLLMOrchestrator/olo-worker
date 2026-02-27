# Planner: One Activity Per Step — Implementation Detail

This document describes how the planner flow is implemented so that **each planner step runs as a separate Temporal activity** (e.g. PLANNER, then PLUGIN:RESEARCH_TOOL, then PLUGIN:EVALUATOR_MODEL), instead of the entire planner subtree running inside a single activity.

---

## 1. High-Level Flow

1. **Workflow** gets a linear execution plan. The plan’s `nodes` list includes one entry for the PLANNER node (e.g. `activityType: "PLANNER"`, `nodeId: "<planner-node-uuid>"`).
2. **Workflow** invokes the **first activity** for that node (PLANNER) with 7 arguments; the 7th (`dynamicStepsJson`) is `null`.
3. **Activity** sees that the node is PLANNER. It runs **planner-only** logic (model + parse + variable injection) and **does not run** the generated steps. It returns a **planner result JSON**: `{ "variableMapJson": "...", "dynamicSteps": [ ... ] }`.
4. **Workflow** parses the result. If it contains `dynamicSteps`, it updates `variableMapJson` from the result, serializes `dynamicSteps` to `dynamicStepsJson`, and then **for each** entry in `dynamicSteps` invokes **one activity** with the same 7 arguments, this time with `dynamicStepsJson` set.
5. **Activity** for a step sees that `nodeId` is **not** in the static pipeline tree but `dynamicStepsJson` is present. It finds the step by `nodeId` in the dynamic steps list, rebuilds an `ExecutionTreeNode` from that step, and runs **only that step** (e.g. one PLUGIN call). It returns the updated variable map JSON string.
6. **Workflow** after the loop calls `applyResultMapping(planJson, variableMapJson)` and returns the workflow result.

So: **one activity = PLANNER** (returns steps), then **one activity per step** (each runs one plugin), then **one activity = applyResultMapping**.

---

## 2. Workflow Layer (`OloKernelWorkflowImpl`)

### 2.1 Entry and plan fetch

- `run(WorkflowInput)` calls `activities.getExecutionPlan(queueNameOrEmpty, workflowInputJson)`.
- If the plan is **not** linear (`planJson == null` or `!"linear":true`), the workflow schedules a single `runExecutionTree` activity and returns (no per-node path).
- If the plan is linear, it parses `planJson` and reads:
  - `initialVariableMapJson`
  - `queueName` (or `planQueueName`)
  - `nodes` — list of `{ "activityType", "nodeId" }` (from `ExecutionPlanBuilder.flatten`).

### 2.2 Per-node loop (linear plan with `nodes`)

- For each `node` in `plan.get("nodes")`:
  - `activityType = node.get("activityType")`, `nodeId = node.get("nodeId")`.
  - **Activity invocation** (7 args):
    ```text
    variableMapJson = untypedActivityStub.execute(
        activityType, String.class,
        planJson, nodeId, variableMapJson, planQueueName, workflowInputJson, null);
    ```
  - So the **first** node (e.g. PLANNER) is executed with `dynamicStepsJson = null`.

### 2.3 Detecting planner result and expanding dynamic steps

- After each activity call, the workflow parses the returned string as JSON: `parseAsMap(variableMapJson)`.
- If the map **contains the key `"dynamicSteps"`**:
  - **Update variable map**: `variableMapJson = parsed.get("variableMapJson")` (as string).
  - **Serialize dynamic steps**: `dynamicStepsJson = MAPPER.writeValueAsString(parsed.get("dynamicSteps"))`.
  - **For each** `step` in `parsed.get("dynamicSteps")`:
    - `stepActivityType = step.get("activityType")` (e.g. `"PLUGIN:EVALUATOR_MODEL"`).
    - `stepNodeId = step.get("nodeId")` (UUID of that step).
    - **Activity invocation** (7 args, this time with `dynamicStepsJson`):
      ```text
      variableMapJson = untypedActivityStub.execute(
          stepActivityType, String.class,
          planJson, stepNodeId, variableMapJson, planQueueName, workflowInputJson, dynamicStepsJson);
      ```
  - **Break** out of the `nodes` loop (no further static nodes are run; the rest was the single PLANNER we just expanded).
- If the result does **not** contain `dynamicSteps`, the loop continues to the next node in the plan.

### 2.4 Result

- After the loop, `variableMapJson` is the final variable map (after PLANNER and all dynamic steps).
- The workflow calls `activities.applyResultMapping(planJson, variableMapJson)` and returns that string.

### 2.5 Fallback

- On any exception in the per-node path, the workflow logs, then calls `activities.runExecutionTree(...)` and returns that result (single-activity fallback).

---

## 3. Activity Interface and Dynamic Activity

### 3.1 Single method (no overloads)

- **Interface** (`OloKernelActivities`): One method only (Temporal does not allow overloads):
  ```java
  String executeNode(String activityType, String planJson, String nodeId, String variableMapJson,
                     String queueName, String workflowInputJson, String dynamicStepsJson);
  ```
- **Implementation** builds a payload map from these 7 parameters and calls the private `executeNode(String payloadJson)`.

### 3.2 How the workflow passes arguments

- The workflow uses `untypedActivityStub.execute(activityType, String.class, args...)`.
- The **activity type** is the first argument to `execute` (used by Temporal for routing); the **payload** is the remaining arguments.
- So the payload has **6** elements: `planJson`, `nodeId`, `variableMapJson`, `queueName`, `workflowInputJson`, `dynamicStepsJson`.
- **Indices** in `EncodedValues`: `0` = planJson, `1` = nodeId, `2` = variableMapJson, `3` = queueName, `4` = workflowInputJson, **`5`** = dynamicStepsJson.

### 3.3 ExecuteNodeDynamicActivity

- Implements `DynamicActivity`. It receives `EncodedValues args` (the 6 payload args).
- Reads:
  - `activityType` from `Activity.getExecutionContext().getInfo().getActivityType()` (not from args).
  - `planJson = args.get(0)`, `nodeId = args.get(1)`, `variableMapJson = args.get(2)`, `queueName = args.get(3)`, `workflowInputJson = args.get(4)`.
  - `dynamicStepsJson = args.get(5, String.class)` (with try/catch; if missing or wrong type, use `null`).
- Calls `delegate.executeNode(activityType, planJson, nodeId, variableMapJson, queueName, workflowInputJson, dynamicStepsJson)`.

---

## 4. Activity Implementation (`OloKernelActivitiesImpl.executeNode`)

The private `executeNode(String payloadJson)` method is the central dispatcher.

### 4.1 Parse payload and resolve context

- Parse payload JSON to get: `planJson`, `nodeId`, `variableMapJson`, `queueName`, `workflowInputJson`, **`dynamicStepsJson`** (optional).
- Validate required fields; derive `tenantId` from `workflowInputJson`.
- Parse `planJson` to get `configJson`, `pipelineName`; load `PipelineConfiguration` and `PipelineDefinition` and the **static** execution tree.

### 4.2 Resolve node: static tree vs dynamic steps

- **Find node in static tree**: `ExecutionTreeNode node = ExecutionTreeNode.findNodeById(pipeline.getExecutionTree(), nodeId)`.
- Build `VariableEngine` from `variableMapJson`, create `PluginInvoker` and `NodeExecutor` (same as for any executeNode).

### 4.3 Branch 1: Dynamic step (node not in pipeline, dynamicStepsJson present)

- **Condition**: `node == null && dynamicStepsJson != null && !dynamicStepsJson.isBlank()`.
- **Resolve step**: `ExecutionTreeNode stepNode = resolveDynamicStep(nodeId, dynamicStepsJson)`.
  - Parses `dynamicStepsJson` to `List<Map<String, Object>>`.
  - Finds the map whose `"nodeId"` equals the requested `nodeId`.
  - Builds one `ExecutionTreeNode` from that map via `nodeFromDynamicStep(step)`.
- **Execute**: `nodeExecutor.executeSingleNode(stepNode, pipeline, variableEngine, queueName)`.
- **Return**: serialized variable map: `MAPPER.writeValueAsString(variableEngine.getExportMap())`.

So for planner-generated steps (UUIDs not in the static tree), the activity **reconstructs** the step from the JSON list and runs it as a single node.

### 4.4 Branch 2: PLANNER node (in static tree)

- **Condition**: `node != null && node.getType() == NodeType.PLANNER`.
- **Planner-only execution**: `List<ExecutionTreeNode> steps = nodeExecutor.executePlannerOnly(node, pipeline, variableEngine, queueName)`.
  - This runs **only** the planner (model + parse + variable injection); it **does not** run the returned steps.
- **Serialize steps for workflow**: For each `ExecutionTreeNode step` in `steps`, build a map via `dynamicStepFromNode(step)` (see below) and add to `dynamicSteps` list.
- **Build planner result JSON**:
  - `variableMapJson` = current variable map after planner (with injected variables).
  - `dynamicSteps` = list of step maps (nodeId, activityType, pluginRef, displayName, inputMappings, outputMappings).
- **Return**: `MAPPER.writeValueAsString(plannerResult)`.

So the **PLANNER activity** returns a **single JSON string** that is an object with two keys: `variableMapJson` and `dynamicSteps`. The workflow uses this to continue the loop with one activity per step.

### 4.5 Branch 3: Other static node

- **Condition**: `node != null` and not PLANNER.
- **Execute**: `nodeExecutor.executeSingleNode(node, pipeline, variableEngine, queueName)`.
- **Return**: serialized variable map.

### 4.6 Branch 4: Node not found

- If `node == null` and we did not resolve from `dynamicStepsJson` (or resolution returned null), throw `IllegalArgumentException("Node not found: " + nodeId)`.

---

## 5. Serialization of Dynamic Steps

### 5.1 Node → Map (`dynamicStepFromNode`)

- Used when the **PLANNER** activity returns; converts each `ExecutionTreeNode` (step) to a map the workflow can pass back as payload.
- **Fields**:
  - `nodeId`: `n.getId()` (UUID string).
  - `activityType`: `"PLUGIN"` or `"PLUGIN:" + n.getPluginRef()`.
  - `pluginRef`, `displayName`.
  - `inputMappings`: list of `{ "pluginParameter", "variable" }` from `ParameterMapping`.
  - `outputMappings`: same shape.
- This map is **identical** to what is needed to reconstruct the node later in `nodeFromDynamicStep`.

### 5.2 Map → Node (`nodeFromDynamicStep`)

- Used when the activity runs a **dynamic step**; receives one element of the `dynamicSteps` list (a map).
- **Reads**: `nodeId`, `displayName`, `pluginRef`, `inputMappings`, `outputMappings`.
- **Builds** `ParameterMapping` lists from the map entries (`pluginParameter`, `variable`).
- **Constructs** `ExecutionTreeNode` with:
  - `NodeType.PLUGIN`, no children, `nodeType = "PLUGIN"`.
  - All feature/pre/post lists empty; params empty; timeouts null.
- So the step is a **leaf PLUGIN node** with the same id, pluginRef, and mappings as when it was created by the planner.

---

## 6. NodeExecutor: Planner-Only vs Single-Node

### 6.1 executePlannerOnly (for PLANNER node)

- **Input**: PLANNER node, pipeline, variableEngine, queueName.
- **Steps**:
  1. Set `LedgerContext` if `ledgerRunId` is set.
  2. Resolve pre/post features for the node; build `NodeExecutionContext`.
  3. **Run pre**: `featureRunner.runPre(resolved, context, registry)`.
  4. **Run planner logic only**: `steps = dispatcher.runPlannerReturnSteps(node, pipeline, variableEngine, queueName)` (see §7). This updates `variableEngine` (injected variables) and returns a list of step nodes; it **does not** attach them to any tree or run them.
  5. **Run post**: `featureRunner.runPostSuccess(...)`.
  6. Return the list of `ExecutionTreeNode` steps.
- On failure: run postError, then rethrow.

So **executePlannerOnly** = pre + planner expansion (model/parse/inject) + post, and returns the step definitions only.

### 6.2 executeSingleNode (for one step or non-PLANNER static node)

- **Input**: one node (either from static tree or reconstructed from dynamic step), pipeline, variableEngine, queueName.
- **PLANNER**: If `node.getType() == NodeType.PLANNER`, throws `IllegalStateException` (PLANNER must be run via `executePlannerOnly`). So the activity **must** use executePlannerOnly for the PLANNER node and never pass a PLANNER node to executeSingleNode.
- **Other nodes**: Sets LedgerContext, then calls `executeNodeSyncSingle(node, ...)` which runs pre → dispatch (e.g. plugin invoke) → postSuccess/postError/finally, and returns.

So **executeSingleNode** is used for:
- Each **dynamic step** (reconstructed from `dynamicStepsJson`), and
- Any **non-PLANNER** node from the static plan (e.g. a PLUGIN node before/after PLANNER in a linear plan that has more than one node).

---

## 7. Dispatcher: runPlannerReturnSteps (planner logic without running steps)

`NodeExecutionDispatcher.runPlannerReturnSteps` performs the same logical steps as the tree-based `executePlannerTree`, but **returns** the list of step nodes instead of attaching them to a tree.

### 7.1 Get plan text (model or variable)

- **Params** from PLANNER node: `planInputVariable` (default `__planner_result`), `modelPluginRef`, `resultVariable`, `userQueryVariable`.
- **If `modelPluginRef` is set** (not interpret-only):
  - Get prompt template for `queueName` (or `"default"`).
  - Read user query from `variableEngine.get(userQueryVariable)` (default `"userQuery"`).
  - Fill template (replace placeholder with user query), put into a prompt variable (e.g. `__planner_prompt`).
  - Call `pluginInvoker.invokeWithVariableMapping(modelPluginRef, inputVarToParam, outputParamToVar, variableEngine)` so the model runs and writes its response into `resultVariable` (e.g. `__planner_result`).
  - `planResultJson` = that response string.
- **If interpret-only** (no model): `planResultJson` = `variableEngine.get(planInputVariable)` (e.g. from a previous FILL_TEMPLATE or PLUGIN node that wrote plan text).

### 7.2 Parse plan into steps (two paths)

**Path A — Subtree creator plugin**

- If PLANNER node has `subtreeCreatorPluginRef`:
  - Call `pluginInvoker.invokeWithInputMap(subtreeCreatorPluginRef, Map.of("planText", planResultJson))`.
  - From output: `variablesToInject` (merged into variableEngine), `steps` (list of step maps).
  - For each step with a `"prompt"` key, inject `__planner_step_<i>_prompt` into variableEngine.
  - **Return** `buildNodesFromCreatorSteps(steps)` (see §7.4).

**Path B — SubtreeBuilder (parser)**

- Read `parser` / `treeBuilder` param (default `"default"`).
- Get `SubtreeBuilder` from `SubtreeBuilderRegistry.get(parserName)`.
- `BuildResult buildResult = builder.build(planResultJson)`.
- Inject `buildResult.variablesToInject()` into variableEngine.
- **Return** `buildResult.nodes()` (list of `ExecutionTreeNode`).

So the planner either uses a **plugin** that returns a list of step maps (path A) or a **parser** that returns a list of tree nodes (path B). In both cases the method returns a list of `ExecutionTreeNode` and does **not** attach them to any tree.

### 7.3 buildNodesFromCreatorSteps (path A only)

- **Input**: `List<Map<String, Object>> steps` from the subtree-creator plugin (each map has at least `pluginRef`, and optionally `prompt`).
- For each step:
  - `pluginRef` from the map (skip if blank).
  - Prompt variable: `__planner_step_<i>_prompt` (already put in variableEngine above).
  - Response variable: `__planner_step_<i>_response`.
  - **Node**: `new ExecutionTreeNode(UUID.randomUUID().toString(), "step-" + i + "-" + pluginRef, NodeType.PLUGIN, List.of(), "PLUGIN", pluginRef, inputMappings(prompt → promptVar), outputMappings(responseText → responseVar), ...)`.
- So each step gets a **new UUID** and fixed input/output variable names; the prompt was already injected so the step will read it from the variable engine when it runs later.

---

## 8. Execution Plan: How the PLANNER Node Appears in the Plan

- **ExecutionPlanBuilder** (e.g. in `getExecutionPlan`) walks the static tree. For **linear** trees it uses `flatten`, which only follows SEQUENCE/GROUP and collects **activity** leaves.
- An **activity** node is a leaf with a type that does work (e.g. PLUGIN, or PLANNER). So the PLANNER node **is** included in the plan as one entry.
- **Plan entry** for that node: `activityType = "PLANNER"`, `nodeId = planner node’s id` (from the static tree).
- So the workflow’s `nodes` list contains exactly one entry for the PLANNER when the pipeline is e.g. ROOT → SEQUENCE → PLANNER. The workflow runs that one node first; the activity returns `{ variableMapJson, dynamicSteps }`; then the workflow runs each dynamic step as a separate activity.

---

## 9. Data Flow Summary

| Stage | Who | Input | Output |
|-------|-----|--------|--------|
| Plan | Workflow | queueName, workflowInputJson | planJson (linear, nodes: [{ activityType, nodeId }, ...]) |
| First activity | Workflow → Activity | planJson, nodeId (PLANNER), variableMapJson, queueName, workflowInputJson, **null** | JSON string: `{ variableMapJson, dynamicSteps }` |
| Parse planner result | Workflow | Activity return string | variableMapJson (updated), dynamicStepsJson (serialized dynamicSteps) |
| Step activity | Workflow → Activity | planJson, stepNodeId (UUID), variableMapJson, queueName, workflowInputJson, **dynamicStepsJson** | JSON string: updated variable map |
| Result | Workflow | planJson, final variableMapJson | activities.applyResultMapping → workflow result string |

---

## 10. Run ledger: single runId for the per-node (planner) run

When the execution plan is linear, the workflow runs **one activity per node** (PLANNER, then each dynamic step). Without a shared run id, each activity would create a **new** run row in **olo_run**, so one logical run would produce multiple run rows. The implementation ensures **one run_id per logical run** as follows.

### 10.1 runId in the plan

- **getExecutionPlan** (in **OloKernelActivitiesImpl**) builds the plan JSON when the tree is linear (or has parallel steps). It now adds a **runId** field: `out.put("runId", UUID.randomUUID().toString())`. That UUID is generated once per plan.
- The workflow receives this plan and passes the **same** `planJson` (including `runId`) to **every** `executeNode` call for that workflow run (PLANNER first, then each dynamic step).

### 10.2 executeNode: reuse runId and ledger lifecycle

- In **executeNode(payloadJson)** the activity parses the plan and reads **runId** from it: `plan.get("runId")`. If present and non-blank, that value is **reused** as the run id for this invocation; otherwise a new UUID is generated (e.g. for older clients or missing field).
- The activity sets **LedgerContext.setRunId(runId)** (thread-local) and passes **runId** into **NodeExecutor** as `ledgerRunId`, so the executor also sets LedgerContext at the start of each node. **NodeLedgerFeature** (and any feature that needs the current run) then sees a non-null runId and persists node records under the same run.
- **runStarted(runId, ...)** is called **only on the first node** in the plan. The activity uses **isFirstNodeInPlan(plan, nodeId)** to detect whether the current node is the first in the plan’s `nodes` or `steps` list. Only then does it call `effectiveRunLedger.runStarted(runId, ...)`, so only **one** row is inserted into **olo_run** per logical run.
- **runEnded(runId, ...)** is called in the activity’s **finally** block for **every** executeNode invocation. Each call updates the same **olo_run** row (same run_id); the last node’s update wins (final status, end_time, etc.). This works for dynamic steps too, since the “last” activity to finish updates the run row.

### 10.3 Summary

| Step | Where | What happens |
|------|--------|----------------|
| 1 | **getExecutionPlan** | Plan JSON includes `runId: "<uuid>"` (one per plan). |
| 2 | **Workflow** | Passes same `planJson` to every executeNode (PLANNER, then each step). |
| 3 | **executeNode** | Reuses runId from plan; sets LedgerContext; passes runId to NodeExecutor. |
| 4 | **First node (e.g. PLANNER)** | `isFirstNodeInPlan` true → `runStarted(runId, ...)` (one olo_run INSERT). |
| 5 | **Every node** | NodeLedgerFeature sees non-null LedgerContext → olo_run_node INSERT/UPDATE. |
| 6 | **Every node (finally)** | `runEnded(runId, ...)` updates olo_run; last invocation sets final state. |

Result: **one olo_run row** and **one olo_run_node row per node** (PLANNER + each dynamic step), all with the same **run_id**. See **docs/run-ledger-schema.md** (§ Run ledger implementation details) for the full description of both execution paths (runExecutionTree and executeNode).

---

## 11. File Reference

| Layer | File | Key methods / types |
|-------|------|----------------------|
| Workflow | `OloKernelWorkflowImpl.java` | `run` (plan fetch, nodes loop, dynamicSteps detection, step loop), `parseAsMap` |
| Activity interface | `OloKernelActivities.java` | `executeNode(..., dynamicStepsJson)` (7 params) |
| Activity impl | `OloKernelActivitiesImpl.java` | `executeNode` (payload), PLANNER branch, dynamic-step branch, `dynamicStepFromNode`, `resolveDynamicStep`, `nodeFromDynamicStep` |
| Dynamic activity | `ExecuteNodeDynamicActivity.java` | `execute` (args 0–5 → delegate.executeNode, arg 5 = dynamicStepsJson) |
| Executor | `NodeExecutor.java` | `executePlannerOnly`, `executeSingleNode` (throws for PLANNER) |
| Dispatcher | `NodeExecutionDispatcher.java` | `runPlannerReturnSteps` (model/parse/inject, return steps), `buildNodesFromCreatorSteps` |
| Plan | `ExecutionPlanBuilder.java` | `flatten` (collects activity nodes including PLANNER into plan nodes) |

This is the full path from workflow to activity to executor to dispatcher and back, with every step needed for “one activity per planner step.”
