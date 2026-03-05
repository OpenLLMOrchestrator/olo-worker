# OLO Worker docs

## Overview and diagrams

- [**Runtime stack diagram**](overview/runtime-stack-diagram.md) — Where OLO fits in the ecosystem (Applications → Agent Frameworks → OLO Runtime → Temporal → Infrastructure). OLO is AI runtime infrastructure, not an agent library.
- [**Diagrams index**](diagrams/README.md) — The five architecture diagrams (runtime stack, execution flow, plugin system, connection+secret flow, event flow) and where each is documented.

---

- [**Docker Hub — description and summary**](docker-hub.md) – Short and full description text for the olo-worker image on Docker Hub (summary, full Markdown, env vars, usage).
- [**Architecture and features**](architecture-and-features.md) – High-level architecture, module map, and detailed description of all features (annotations, feature framework, debug feature, plugins, execution tree, bootstrap, workflow/activities, input, run ledger). **§3.8.1 Dynamic tree node creation (PLANNER expansion):** planner proposes `NodeSpec` list, worker materializes via `DynamicNodeFactory.expand`; protocol types, flow, ExpansionLimits (max depth, max nodes, max invocations), idempotency guard for activity retry, depth-only cycle prevention, PLANNER+ASYNC disallowed.
- [**Run ledger schema**](run-ledger-schema.md) – When `OLO_RUN_LEDGER=true`: tables **olo_run**, **olo_run_node**, **olo_config** (UUID/TIMESTAMPTZ); bootstrap script; migrations. **Run ledger implementation details:** two paths (runExecutionTree vs executeNode), single runId for per-node runs (plan.runId, runStarted only on first node), LedgerContext and NodeExecutor.ledgerRunId, JdbcLedgerStore JSONB/PGobject and attempt column.
- [**Workflow start: deserialize and push to session**](workflow-start-session-storage.md) – `OLO_SESSION_DATA`, storing `WorkflowInput` at tenant-scoped key `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`.
- [**Pipeline configuration: how to use**](pipeline-configuration-how-to.md) – named pipelines, input contract, variable registry, scope, execution tree.
- [**3.x Variable Execution Model**](variable-execution-model.md) – IN/INTERNAL/OUT, inputContract alignment, strict mode, type validation, OUT assignment.
- [**Versioned config strategy**](versioned-config-strategy.md) – Config file version, plugin contract version, feature contract version; validation before run to stop on breaking changes; semantic compatibility rules.
- [**Feature ordering diagram**](feature-ordering-diagram.md) – Phase flow (PRE → NODE → POST_SUCCESS/POST_ERROR → FINALLY) and resolved feature merge order; single-page reference.
- [**Multi-tenant separation**](multi-tenant.md) – Tenant-scoped Redis keys (tenant-first: `<tenantId>:olo:...` for session, config, quota), **OLO_DEFAULT_TENANT_ID** (or fixed default UUID when unset), global config as Map&lt;tenantKey, Map&lt;queue, config&gt;&gt;, DB tenant_id.
- [**Planner: one activity per step**](planner-one-activity-per-step-implementation.md) – How the planner flow runs as separate activities (PLANNER, then each dynamic step); plan JSON, executeNode, runId in plan and single run per logical run (§10).
- [**Node type catalog**](node-type-catalog.md) – SEQUENCE, IF, SWITCH (CASE), ITERATOR, FORK, JOIN, PLUGIN; params and merge strategies.
- [**Use case: Recursive Research Planner**](use-cases/recursive-research-planner.md) – Planner-spawns-planner: initial tree ROOT → MASTER_PLANNER; master expands to Research Profile + Financial Sub-Planner + Risk Sub-Planner; sub-planners expand when executed (lazy, depth-first). Pipeline: `olo-recursive-research`.
- [**Plan: Immutable config, version pinning, tenant limiter**](plan-immutable-config-version-pinning-tenant-limiter.md) – Design plan for immutable config snapshots, execution version pinning, and tenant execution limiter (thread isolation). Sample: [`sample-pipeline-configuration.json`](sample-pipeline-configuration.json). **Config folder:** [`config/`](../config/) – one file per task queue name (e.g. `olo-chat-queue-ollama.json`, `olo-rag-queue-openai.json`) plus [`default.json`](../config/default.json).
- [**Secret architecture**](secret-architecture.md) – Plugin-based, tenant-aware, runtime-resolvable secrets: vault-agnostic references (${secret:openai.api_key}), Secret Catalog, SecretResolver, SecretProvider plugins, multi-tenant scoping, caching, rotation, masking. Integrates with Connection Manager. V1: SecretResolver, EnvProvider, VaultProvider, masking.
- [**Plugin design**](plugin-design.md) – Plugin contract (OloPlugin: name, type, schema, createRuntime), PluginRegistry, ConnectionSchema, typed runtimes (ModelClient, VectorClient, etc.), thread safety, lifecycle. Integrates with Connection Manager and execution tree.
- [**Feature design**](feature-design.md) – Feature contract (phases PRE, POST_SUCCESS, POST_ERROR, FINALLY), FeatureRegistry, FeatureAttachmentResolver, privilege (internal vs community), NodeExecutionContext, lifecycle. Cross-cutting behavior around node execution.
- [**Execution tree design**](execution-tree-design.md) – Execution tree as declarative pipeline (node types, tree structure, variable model, execution flow), NodeExecutor, ExecutionEngine, scope, integration with plugins and features.
- [**Event communication architecture**](arcitecture/event-communication-architecture.md) – Execution events (runId, chat UI), connection/plugin observability events, lifecycle events (cache invalidation), transport, masking. Aligns with execution-events, feature-design, connection-manager-design.
- [**Connection Manager design**](connection-manager-design.md) – Runtime bridge between pipelines and plugins: connection abstraction (DB table, config, secret_ref), plugin registry integration, runtime instance creation, typed clients (Model/Vector/Tool/Storage), multi-tenant resolution, caching, hot reload, secret resolution, pipeline overrides, lazy init, type validation, health check, observability, scoped access, SDK integration, connection types, fallback. Suggested implementation order.
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
