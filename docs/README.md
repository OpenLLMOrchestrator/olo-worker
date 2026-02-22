# OLO Worker docs

- [**Architecture and features**](architecture-and-features.md) – High-level architecture, module map, and detailed description of all features (annotations, feature framework, debug feature, plugins, execution tree, bootstrap, workflow/activities, input).
- [**Workflow start: deserialize and push to session**](workflow-start-session-storage.md) – `OLO_SESSION_DATA`, storing `WorkflowInput` at `<prefix><transactionId>:USERINPUT`.
- [**Pipeline configuration: how to use**](pipeline-configuration-how-to.md) – named pipelines, input contract, variable registry, scope, execution tree.
- [**3.x Variable Execution Model**](variable-execution-model.md) – IN/INTERNAL/OUT, inputContract alignment, strict mode, type validation, OUT assignment.
- [**Versioned config strategy**](versioned-config-strategy.md) – Config file version, plugin contract version, feature contract version; validation before run to stop on breaking changes.
- [**Node type catalog**](node-type-catalog.md) – SEQUENCE, IF, SWITCH (CASE), ITERATOR, FORK, JOIN, PLUGIN; params and merge strategies. Sample: [`sample-pipeline-configuration.json`](sample-pipeline-configuration.json). **Config folder:** [`config/`](../config/) – one file per task queue name (e.g. `olo-chat-queue-oolama.json`, `olo-rag-queue-openai.json`) plus [`default.json`](../config/default.json).
- **Bootstrap** – The `olo-worker-bootstrap` module creates configuration from env, validates task queues (exits if OLO_QUEUE is empty), loads pipeline config for all queues into the global context (Redis → DB → file → default; file-loaded config is written back to Redis), and returns a `GlobalContext` holding in-memory `OloConfig` and a map of queue name → deserialized pipeline configuration. The worker calls `OloBootstrap.initialize()` and uses `GlobalContext.getConfig()`, `GlobalContext.getTaskQueues()`, and `GlobalContext.getPipelineConfigByQueue()`.
- **Execution context** – The `olo-worker-execution-context` module provides a per-workflow **local context**. When a new workflow starts, create a `LocalContext` for that workflow by calling `LocalContext.forQueue(queueName)`: it obtains a **deep copy** of the pipeline configuration (execution tree) for that queue from the global context and stores it in the local context so the workflow uses an isolated copy for its run.
- **Features** – The `olo-worker-features` module provides the common feature contracts and registry; concrete features live in separate modules (e.g. **olo-feature-debug** for **DebuggerFeature**). Feature **phases**: PRE, POST_SUCCESS, POST_ERROR, FINALLY, PRE_FINALLY (see [architecture-and-features.md](architecture-and-features.md)). Features are invoked before/after execution tree nodes. Nodes can have **preExecution** / **postExecution** lists and **featureRequired** / **featureNotRequired** overrides. For queues ending in **-debug**, the **debug** feature is auto-attached to all nodes (unless a node opts out via **featureNotRequired**). Use **FeatureAttachmentResolver.resolve(node, queueName, scopeFeatureNames, registry)** to get effective pre/post lists; then invoke registered features from **FeatureRegistry**.

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
