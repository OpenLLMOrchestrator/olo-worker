# OLO Worker docs

- [**Architecture and features**](architecture-and-features.md) – High-level architecture, module map, and detailed description of all features (annotations, feature framework, debug feature, plugins, execution tree, bootstrap, workflow/activities, input, run ledger). **§3.8.1 Dynamic tree node creation (PLANNER expansion):** planner proposes `NodeSpec` list, worker materializes via `DynamicNodeFactory.expand`; protocol types, flow, ExpansionLimits (max depth, max nodes, max invocations), idempotency guard for activity retry, depth-only cycle prevention, PLANNER+ASYNC disallowed.
- [**Run ledger schema**](run-ledger-schema.md) – When `OLO_RUN_LEDGER=true`: tables **olo_run**, **olo_run_node**, **olo_config** (UUID/TIMESTAMPTZ); bootstrap script; migrations from older schemas.
- [**Workflow start: deserialize and push to session**](workflow-start-session-storage.md) – `OLO_SESSION_DATA`, storing `WorkflowInput` at tenant-scoped key `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`.
- [**Pipeline configuration: how to use**](pipeline-configuration-how-to.md) – named pipelines, input contract, variable registry, scope, execution tree.
- [**3.x Variable Execution Model**](variable-execution-model.md) – IN/INTERNAL/OUT, inputContract alignment, strict mode, type validation, OUT assignment.
- [**Versioned config strategy**](versioned-config-strategy.md) – Config file version, plugin contract version, feature contract version; validation before run to stop on breaking changes; semantic compatibility rules.
- [**Feature ordering diagram**](feature-ordering-diagram.md) – Phase flow (PRE → NODE → POST_SUCCESS/POST_ERROR → FINALLY) and resolved feature merge order; single-page reference.
- [**Multi-tenant separation**](multi-tenant.md) – Tenant-scoped Redis keys (tenant-first: `<tenantId>:olo:...` for session, config, quota), **OLO_DEFAULT_TENANT_ID** (or fixed default UUID when unset), global config as Map&lt;tenantKey, Map&lt;queue, config&gt;&gt;, DB tenant_id.
- [**Node type catalog**](node-type-catalog.md) – SEQUENCE, IF, SWITCH (CASE), ITERATOR, FORK, JOIN, PLUGIN; params and merge strategies.
- [**Use case: Recursive Research Planner**](use-cases/recursive-research-planner.md) – Planner-spawns-planner: initial tree ROOT → MASTER_PLANNER; master expands to Research Profile + Financial Sub-Planner + Risk Sub-Planner; sub-planners expand when executed (lazy, depth-first). Pipeline: `olo-recursive-research`.
- [**Plan: Immutable config, version pinning, tenant limiter**](plan-immutable-config-version-pinning-tenant-limiter.md) – Design plan for immutable config snapshots, execution version pinning, and tenant execution limiter (thread isolation). Sample: [`sample-pipeline-configuration.json`](sample-pipeline-configuration.json). **Config folder:** [`config/`](../config/) – one file per task queue name (e.g. `olo-chat-queue-oolama.json`, `olo-rag-queue-openai.json`) plus [`default.json`](../config/default.json).
- **Bootstrap** – The `olo-worker-bootstrap` module creates configuration from env, validates task queues (exits if OLO_QUEUE is empty), loads pipeline config per tenant into **GlobalConfigurationContext** (runtime config store; Redis → DB → file → default; file-loaded config written back to Redis), and returns a context wrapper. **OloBootstrap.initialize()** returns **BootstrapContext** (config, task queues, tenants, pipeline config by queue; **putContributorData** / **getContributorData** for contributor metadata). **OloBootstrap.initializeWorker()** returns **WorkerBootstrapContext** (extends BootstrapContext; adds runLedger, sessionCache, **PluginExecutorFactory**, **runResourceCleanup()**). Bootstrap runs **BootstrapContributor**s after config load (e.g. planner attaches design contract to context). The worker calls **initializeWorker()** and uses the context for config, queues, plugin executor, and shutdown cleanup.
- **Execution context** – The `olo-worker-execution-context` module provides a per-workflow **local context**. When a new workflow starts, create a `LocalContext` by calling `LocalContext.forQueue(tenantId, queueName)` (or with optional configVersion): it obtains a **deep copy** of the pipeline configuration for that tenant and queue from the global context. **ExecutionConfigSnapshot** can carry an optional **runId** for the run ledger (set in activity, passed to NodeExecutor for LedgerContext).
- **Features** – The `olo-worker-features` module provides the common feature contracts and registry; concrete features live in separate modules (e.g. **olo-feature-debug**, **olo-feature-quota**, **olo-feature-metrics**). Feature **phases**: PRE, POST_SUCCESS, POST_ERROR, FINALLY, PRE_FINALLY (see [architecture-and-features.md](architecture-and-features.md)). Features are invoked before/after execution tree nodes. Nodes can have **preExecution** / **postSuccessExecution** / **postErrorExecution** / **finallyExecution** and **featureRequired** / **featureNotRequired**. For queues ending in **-debug**, the **debug** feature is auto-attached. Use **FeatureAttachmentResolver.resolve(node, queueName, scopeFeatureNames, registry)** to get effective pre/post lists; then invoke registered features from **FeatureRegistry**.

## Sample workflow input (copy-paste)

Use this JSON as the workflow input when starting an OLO Temporal workflow.

- **Pretty** (readable): [`sample-workflow-input.json`](sample-workflow-input.json)
- **One line** (CLI/API): [`sample-workflow-input-oneline.txt`](sample-workflow-input-oneline.txt)

### Pretty (copy from here)

```json
{
  "version": "2.0",
  "inputs": [
    {
      "name": "input1",
      "displayName": "input1",
      "type": "STRING",
      "storage": {
        "mode": "LOCAL"
      },
      "value": "Hi!"
    },
    {
      "name": "input2",
      "displayName": "input2",
      "type": "STRING",
      "storage": {
        "mode": "CACHE",
        "cache": {
          "provider": "REDIS",
          "key": "olo:worker:8huqpd42mizzgjOhJEH9C:input:input2"
        }
      }
    },
    {
      "name": "input3",
      "displayName": "input3",
      "type": "FILE",
      "storage": {
        "mode": "LOCAL",
        "file": {
          "relativeFolder": "rag/8huqpd42mizzgjOhJEH9C/",
          "fileName": "readme.md"
        }
      }
    }
  ],
  "context": {
    "tenantId": "",
    "groupId": "",
    "roles": ["PUBLIC", "ADMIN"],
    "permissions": ["STORAGE", "CACHE", "S3"],
    "sessionId": "<UUID>"
  },
  "routing": {
    "pipeline": "chat-queue-ollama",
    "transactionType": "QUESTION_ANSWER",
    "transactionId": "8huqpd42mizzgjOhJEH9C"
  },
  "metadata": {
    "ragTag": null,
    "timestamp": 1771740578582
  }
}
```

### One line (for CLI / single-argument APIs)

```
{"version":"2.0","inputs":[{"name":"input1","displayName":"input1","type":"STRING","storage":{"mode":"LOCAL"},"value":"Hi!"},{"name":"input2","displayName":"input2","type":"STRING","storage":{"mode":"CACHE","cache":{"provider":"REDIS","key":"olo:worker:8huqpd42mizzgjOhJEH9C:input:input2"}}},{"name":"input3","displayName":"input3","type":"FILE","storage":{"mode":"LOCAL","file":{"relativeFolder":"rag/8huqpd42mizzgjOhJEH9C/","fileName":"readme.md"}}}],"context":{"tenantId":"","groupId":"","roles":["PUBLIC","ADMIN"],"permissions":["STORAGE","CACHE","S3"],"sessionId":"<UUID>"},"routing":{"pipeline":"chat-queue-ollama","transactionType":"QUESTION_ANSWER","transactionId":"8huqpd42mizzgjOhJEH9C"},"metadata":{"ragTag":null,"timestamp":1771740578582}}
```

### Usage

- **Temporal CLI**: pass the one-line JSON as the workflow input argument.
- **Java**: `WorkflowInput.fromJson(jsonString)` then use as workflow input.
- **REST / other**: send the JSON body; ensure `Content-Type: application/json` and use the one-line form if needed for a single field.
