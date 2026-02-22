# Execution tree node type catalog

This document defines the node types supported by the execution engine. In code, the node **type** field is represented by the **`NodeType`** enum in `olo-worker-execution-tree` (`com.olo.executiontree.tree.NodeType`). JSON config uses the enum name as string (e.g. `"type": "SEQUENCE"`); unknown values deserialize as `UNKNOWN`.

- **Phase 1 (Must Have):** SEQUENCE, PLUGIN, IF, SWITCH/CASE, ITERATOR, FORK, JOIN — **implemented**.
- **Phase 2 (Enterprise Ready):** TRY_CATCH, RETRY, SUB_PIPELINE, EVENT_WAIT — **implemented**.
- **Phase 3 (AI Advantage):** LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION — **implemented**.

Inner nodes (containers) can be any of the above; leaf nodes include PLUGIN. SWITCH children must be CASE nodes.

---

## 1. SEQUENCE

**Purpose:** Execute children in order.

- **type:** `"SEQUENCE"`
- **children:** List of nodes; executed left-to-right in order.
- **params:** None required.

---

## 2. IF (Conditional branch)

**Purpose:** Branch execution based on an expression or variable.

- **type:** `"IF"`
- **children:** `[thenBranch, elseBranch]` — first child runs when condition is true, second when false. Optional: only thenBranch (elseBranch can be omitted or empty).
- **params:**
  - **conditionVariable** (string): Variable name whose value is evaluated as boolean (true → then, false → else). If missing, then branch runs.
  - **expression** (string, optional): Future; expression language for richer conditions.

---

## 3. SWITCH (Multi-branch condition)

**Purpose:** Choose one branch from many based on a value.

- **type:** `"SWITCH"`
- **children:** Must be **CASE** nodes. Each CASE has a value; the first matching case runs.
- **params:**
  - **switchVariable** (string): Variable name whose value is compared to each CASE’s **caseValue**.

### CASE (child of SWITCH)

- **type:** `"CASE"`
- **children:** One or more nodes (typically one SEQUENCE or PLUGIN) to run when this case matches.
- **params:**
  - **caseValue** (string or number): Value to match against the SWITCH’s switchVariable. First CASE with matching value runs; no match means no branch runs (or default if defined).

---

## 4. ITERATOR (Loop / ForEach)

**Purpose:** Run a child subgraph once per item in a collection.

- **type:** `"ITERATOR"`
- **children:** One node (e.g. SEQUENCE or PLUGIN) — the body to run per iteration.
- **params:**
  - **collectionVariable** (string): Variable name holding the collection (list/array) to iterate over.
  - **itemVariable** (string): Variable name to set to the current item in each iteration (must be declared in variableRegistry, e.g. INTERNAL).
  - **indexVariable** (string, optional): Variable name for current index (0-based).

---

## 5. FORK (Parallel split)

**Purpose:** Run multiple branches concurrently.

- **type:** `"FORK"`
- **children:** Two or more nodes; each branch runs in parallel (when the runtime supports it).
- **params:** None required.

**Note:** Full parallelism requires the runtime to schedule branches concurrently (e.g. async or worker tasks). A single-threaded executor may run branches sequentially; JOIN merge strategy still applies when branches complete.

---

## 6. JOIN (Parallel merge)

**Purpose:** Synchronize after FORK; merge results with a defined strategy. Supports built-in strategies and a **plugin** strategy (JOIN node with pluginRef).

- **type:** `"JOIN"`
- **children:** Typically one node per FORK branch (or a single child that consumes merged context). Must define merge strategy.
- **params:**
  - **mergeStrategy** (string): **Required.** One of:
    - **ALL** — Run all branches (in order); all complete before proceeding.
    - **ANY** — Run only the first child (first branch wins).
    - **FIRST_WINS** — Same as ANY; run only the first child.
    - **LAST_WINS** — Run all children in order; last child’s variable writes win (overwrite earlier ones).
    - **MAJORITY** — (Future) Proceed when a majority of branches complete.
    - **REDUCE** — (Future) Custom aggregator over branch results.
    - **PLUGIN** — Run all children, then invoke a **plugin** to merge: use **pluginRef**, **inputMappings**, **outputMappings** on the JOIN node (same as a PLUGIN node). The plugin receives the current variable map (after all branches ran) and returns merged output.
- When **mergeStrategy** is **PLUGIN**, the JOIN node must have **pluginRef** (and typically **inputMappings** / **outputMappings**) set; otherwise the plugin step is skipped.

Without a defined merge strategy, JOIN cannot be executed correctly; it is required for correct semantics.

---

## 7. PLUGIN

**Purpose:** Invoke a plugin (e.g. model executor); map variables to/from plugin parameters.

- **type:** `"PLUGIN"`
- **children:** None.
- **nodeType:** Category (e.g. `MODEL_EXECUTOR`).
- **pluginRef:** Plugin id from scope.
- **inputMappings:** variable → pluginParameter.
- **outputMappings:** pluginParameter → variable.
- **params:** Plugin-specific (optional).

---

## Summary

| Type     | Purpose              | Children              | Key params                          |
|----------|----------------------|------------------------|-------------------------------------|
| SEQUENCE | Execute in order     | Any                   | —                                   |
| IF       | Conditional branch   | [then, else]          | conditionVariable                   |
| SWITCH   | Multi-branch         | CASE nodes            | switchVariable                      |
| CASE     | One branch of SWITCH| Body                  | caseValue                           |
| ITERATOR | Loop / ForEach       | One body              | collectionVariable, itemVariable   |
| FORK     | Parallel split       | Two or more           | —                                   |
| JOIN     | Parallel merge       | Per-branch or one     | mergeStrategy (ALL, ANY, FIRST_WINS, LAST_WINS, PLUGIN, …) |
| PLUGIN   | Call plugin          | None                  | pluginRef, input/output Mappings   |

---

## Phase 2 (Enterprise Ready) — implemented

### 8. TRY_CATCH

**Purpose:** Run a body in a try block; on error run catch body and optionally set an error variable.

- **type:** `"TRY_CATCH"`
- **children:** `[tryBody, catchBody]` — first child is try block, second is catch block (optional).
- **params:**
  - **errorVariable** (string, optional): Variable name to set to the error message or exception in the catch block.
- **Status:** Implemented: run first child (try); on any Throwable set errorVariable (if present) and run second child (catch).

### 9. RETRY

**Purpose:** Retry a child node with a retry policy (max attempts, backoff, retryable errors).

- **type:** `"RETRY"`
- **children:** One node to retry.
- **params:**
  - **maxAttempts** (number): Maximum attempts (e.g. 3).
  - **initialIntervalMs** (number, optional): Initial backoff in ms.
  - **backoffCoefficient** (number, optional): Multiplier for backoff.
  - **retryableErrors** (array of string, optional): Exception/error types to retry; others fail immediately.
- **Status:** Implemented: retry child up to maxAttempts with optional backoff; retryableErrors filters which exceptions are retried.

### 10. SUB_PIPELINE

**Purpose:** Invoke another pipeline by name (or id); pass variables in/out; supports composition.

- **type:** `"SUB_PIPELINE"`
- **children:** None (or config reference).
- **params:**
  - **pipelineRef** (string): Name or id of the sub-pipeline to run.
  - **inputMapping** (object or array, optional): Map current variables to sub-pipeline input.
  - **outputMapping** (object or array, optional): Map sub-pipeline output to current variables.
- **Status:** Implemented: runs sub-pipeline from same config (same VariableEngine); requires `ExecutionEngine.run(config, entryPipelineName, ...)`.

### 11. EVENT_WAIT

**Purpose:** Pause execution until an external event (e.g. webhook, message, signal); timeout optional.

- **type:** `"EVENT_WAIT"`
- **children:** None.
- **params:**
  - **eventKey** (string): Identifier for the event/signal to wait on.
  - **timeoutVariable** (string, optional): Variable name for timeout duration (e.g. seconds).
  - **resultVariable** (string, optional): Variable to store the event payload when received.
- **Status:** Implemented: no blocking in activity; if resultVariable already set it is returned; otherwise no-op (workflow/signal required for real wait).

---

## Phase 3 (AI Advantage) — implemented

### 12. LLM_DECISION

**Purpose:** Call an LLM to make a decision (e.g. branch, classification, next step); write result to a variable.

- **type:** `"LLM_DECISION"`
- **children:** Optional follow-up nodes based on decision (or use SWITCH on output variable).
- **params:**
  - **pluginRef** (string): Model-executor plugin id.
  - **promptVariable** (string): Variable containing the decision prompt.
  - **outputVariable** (string): Variable to write the LLM response (e.g. for IF/SWITCH downstream).
  - **options** (object, optional): Model options (temperature, maxTokens, etc.).
- **Status:** Implemented: calls pluginRef with promptVariable → "prompt", writes responseText to outputVariable.

### 13. TOOL_ROUTER

**Purpose:** Route to one of several tools (plugins) based on LLM or rule output; multi-tool orchestration.

- **type:** `"TOOL_ROUTER"`
- **children:** Nodes representing tool invocations or SWITCH/CASE by tool name.
- **params:**
  - **inputVariable** (string): Variable containing the user intent or tool choice.
  - **toolMapping** (object or array, optional): Map choice values to pluginRef or child indices.
- **Status:** Implemented: routes by inputVariable; children may have caseValue or toolValue in params; first matching child runs, else first child.

### 14. EVALUATION

**Purpose:** Run an evaluation step (e.g. score output quality, safety, or correctness) and store result.

- **type:** `"EVALUATION"`
- **children:** Optional: run different paths by score (e.g. retry if below threshold).
- **params:**
  - **evaluatorRef** (string): Plugin or evaluator id.
  - **inputVariable** (string): Variable holding content to evaluate.
  - **outputVariable** (string): Variable to write score or result.
  - **threshold** (number, optional): Threshold for branching (Phase 2 RETRY or IF).
- **Status:** Implemented: calls evaluatorRef with inputVariable → "input", writes result/score to outputVariable.

### 15. REFLECTION

**Purpose:** Run a reflection step (e.g. self-critique, improve answer) and write refined output to a variable.

- **type:** `"REFLECTION"`
- **children:** Optional: chain multiple reflection passes.
- **params:**
  - **pluginRef** (string): Model or reflection plugin id.
  - **inputVariable** (string): Variable holding content to reflect on.
  - **outputVariable** (string): Variable to write refined content.
  - **promptTemplate** (string, optional): Override reflection prompt.
- **Status:** Implemented: calls pluginRef with inputVariable → "prompt", writes responseText to outputVariable.

---

## Coverage review (phases)

| Phase | Type          | Catalog | Executor | Notes                    |
|-------|---------------|---------|----------|--------------------------|
| **1** | SEQUENCE      | Yes     | Yes      | Implemented              |
| **1** | PLUGIN        | Yes     | Yes      | Implemented              |
| **1** | IF            | Yes     | Yes      | Implemented              |
| **1** | ITERATOR      | Yes     | Yes      | Implemented              |
| **1** | FORK          | Yes     | Yes      | Sequential today         |
| **1** | JOIN          | Yes     | Yes      | mergeStrategy required   |
| **2** | TRY_CATCH     | Yes     | Yes      | Implemented              |
| **2** | RETRY         | Yes     | Yes      | Implemented              |
| **2** | SUB_PIPELINE  | Yes     | Yes      | Implemented (needs config) |
| **2** | EVENT_WAIT    | Yes     | Yes      | No-op in activity        |
| **3** | LLM_DECISION  | Yes     | Yes      | Implemented              |
| **3** | TOOL_ROUTER   | Yes     | Yes      | Implemented              |
| **3** | EVALUATION    | Yes     | Yes      | Implemented              |
| **3** | REFLECTION    | Yes     | Yes      | Implemented              |

SWITCH and CASE are part of Phase 1 (multi-branch); they are implemented.

See [variable-execution-model.md](variable-execution-model.md) for variable rules; [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) for JSON structure.
