# Execution Events for Chat UI

Execution events are **semantic steps** emitted during a run so the chat UI can show readable, debuggable, collapsible progress—not raw logs.

## Event types and UI representation

| Event Type          | UI Representation   | When emitted                    |
|---------------------|---------------------|----------------------------------|
| `planner.started`   | ⏳ Planner started | Before planner node runs         |
| `planner.completed` | 🧠 Planner output  | After planner node completes     |
| `tool.started`      | 🔧 Tool executing   | Before plugin (tool) node runs   |
| `tool.completed`    | ✅ Tool result      | After tool node completes        |
| `model.started`     | 🤖 Model call      | Before model plugin runs         |
| `model.token`       | streaming text      | (Reserved for streaming; future) |
| `model.completed`   | 🤖 Model result    | After model node completes       |
| `human.required`    | ✋ Approval required| (Reserved for approval flows)    |
| `workflow.started`  | workflow started   | Run start                        |
| `workflow.completed`| 🎉 Done            | Run success                      |
| `workflow.failed`   | ❌ Error           | Run failure                      |

Model vs tool is inferred from plugin id: if it contains `model`, `executor`, `ollama`, `gpt`, `openai`, or `litellm`, the node is treated as a model; otherwise as a tool.

## RunId: use the backend’s runId

The worker uses the **same runId as the backend** when the workflow input provides it, so events are keyed for the run the UI is already tracking.

- **Workflow input**: The backend sets `context.runId` when starting the workflow (e.g. `d2014cc0-761c-4f38-b9e9-cfaf19ff7c22`).
- **Tree run** (`runExecutionTree`): `TreeContextResolver` uses `workflowInput.getContext().getRunId()` when non-blank; otherwise it generates a new UUID.
- **Per-node run** (plan + `executeNode`): The execution plan is built with the same `context.runId`, so every activity uses that runId and events are stored under it.

Events are always stored per run in the in-memory sink keyed by this `runId`.

## Human-readable messages

Events carry a **human-readable message** for the chat UI:

- **Planner completed**: The planner result includes a **numbered list** of suggested steps, e.g.  
  `Planner suggested:`  
  `1. RESEARCH_TOOL`  
  `2. CRITIC_TOOL`  
  `3. EVALUATOR_MODEL`  
  This is in the event **label** and in **payload.message**.
- **Tool/model completed**: If the plugin returns `responseText` or `content`, a short snippet (e.g. 200 chars) is added to **payload.message**.
- **Errors**: On failure, **payload.error** is set with the error message.

The UI should show **label** or **payload.message** as the main text for each step.

## Event JSON shape

Each event is a JSON object:

```json
{
  "eventType": "planner.completed",
  "label": "Planner suggested:\n1. RESEARCH_TOOL\n2. CRITIC_TOOL",
  "payload": {
    "message": "Planner suggested:\n1. RESEARCH_TOOL\n2. CRITIC_TOOL",
    "pluginId": null,
    "queueName": "olo-chat-queue-ollama-debug"
  },
  "timestampMillis": 1709300000000,
  "nodeId": "91665ec4-fa1e-4dee-a8f0-4a735112e9c3"
}
```

- **eventType**: one of the types in the table above.
- **label**: human-readable short label (or full message for planner).
- **payload**: optional map; often includes **message** (human-readable text), **pluginId**, **queueName**, and **error** on failure.
- **timestampMillis**: epoch millis.
- **nodeId**: optional; set for node-scoped events.

## Logging

Every time an event is emitted, the sink logs it so you can trace the flow in worker logs:

```
Execution event | runId=fa1ace1a-c241-4e7a-befd-18b1307be807 | eventType=workflow.started | message=Workflow started
Execution event | runId=fa1ace1a-c241-4e7a-befd-18b1307be807 | eventType=planner.started | message=Planner started
Execution event | runId=fa1ace1a-c241-4e7a-befd-18b1307be807 | eventType=planner.completed | message=Planner suggested: 1. ...
Execution event | runId=fa1ace1a-c241-4e7a-befd-18b1307be807 | eventType=workflow.completed | message=Done
```

**message** is taken from **payload.message** when present, otherwise from **label**.

## How the chat UI can consume events

1. **RunId**  
   The backend starts the workflow with `context.runId` set. The worker uses that runId for the whole run (tree or per-node). The UI already has this runId from the backend (e.g. from the run or session API).

2. **Reading events**  
   - **In-process**: If the chat UI or an API runs in the same JVM as the worker, it can hold a reference to `InMemoryExecutionEventSink` and call `getEvents(runId)` to get a snapshot of events for that run (e.g. poll or after completion).  
   - **Out-of-process**: Add a REST or SSE API that takes `runId` and returns events from the same sink instance the worker uses, or have the worker push events to the backend (e.g. via `callbackBaseUrl`).

3. **UI behavior**  
   - Render each event as a **collapsible step** using `eventType` for icon/emoji and **label** or **payload.message** for the main text.  
   - For `planner.completed`, show the numbered list (label or payload.message) with line breaks.  
   - Use **payload** for expandable details (plugin name, error, etc.).  
   - Use **timestampMillis** for ordering and optional relative time.

## Implementation notes

- **ExecutionEventSink**: interface in `olo-run-ledger`; default implementation **InMemoryExecutionEventSink** stores events by `runId` and logs every emit.
- **ExecutionEventsFeature**: PRE_FINALLY feature that emits planner/tool/model and workflow-level events when a sink is registered. It is **auto-attached** (like `ledger-node`): when the feature is registered, `FeatureResolver` adds it to the effective feature list for every node, so you do **not** need to add `execution-events` to the pipeline scope. Planner, tool, and model steps are always emitted when the sink is enabled.
- **Workflow/run boundaries**: `workflow.started` is emitted when a run starts (tree or first node); `workflow.completed` / `workflow.failed` when the run ends.
- **Planner summary**: `PlannerTreeExecutor` builds a human-readable “Planner suggested: 1. … 2. …” string from the expanded steps and returns it so the feature can set the event label and payload.message.
