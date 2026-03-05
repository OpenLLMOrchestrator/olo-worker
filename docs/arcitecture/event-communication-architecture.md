# Olo Event Communication Architecture — Specification

This document defines how **events** are produced, transported, and consumed across the Olo runtime: execution events (for chat UI and observability), connection and plugin lifecycle events, cache invalidation signals, and optional out-of-process streaming. It aligns with [execution-events](../execution-events.md), [feature-design](feature-design.md), [connection-manager-design](connection-manager-design.md), [plugin-design](plugin-design.md), and [secret-architecture](secret-architecture.md).

---

## Goals

- **Unified view** — One place that describes all event-like communication in Olo: what events exist, who produces them, who consumes them, and how they flow.
- **Execution visibility** — Semantic execution events (planner, tool, model, workflow) keyed by runId so UIs and tooling can show progress without parsing logs.
- **Lifecycle and observability** — Connection resolution, runtime creation, plugin load, and errors emit events for debugging and monitoring; no secret values in events.
- **Cache and invalidation** — Events such as **ConnectionUpdatedEvent** and **SecretRotatedEvent** drive cache invalidation so runtimes and config stay consistent.
- **Extensibility** — Event shapes and sinks are defined so new consumers (SSE, webhooks, message buses) can be added without changing producers.

---

## What Counts as an “Event” in Olo

In this document, **event** means a **discrete, timestamped occurrence** produced by the worker or a subsystem, consumed by in-process sinks (e.g. in-memory store, log) or by out-of-process consumers (e.g. chat UI, metrics pipeline). We do **not** mean Temporal workflow/activity completion or internal method calls unless they are explicitly published as events.

| Category | Purpose | Examples |
|----------|---------|----------|
| **Execution events** | Semantic steps for run visibility (e.g. chat UI, debugging). Keyed by **runId**. | `workflow.started`, `planner.completed`, `tool.completed`, `model.completed`, `workflow.completed` |
| **Connection/plugin observability** | Resolution, runtime creation, errors. Used for monitoring and debugging. | **ConnectionResolvedEvent**, **RuntimeCreatedEvent**, **ConnectionErrorEvent** |
| **Lifecycle / invalidation** | Signals that drive cache invalidation or config refresh. | **ConnectionUpdatedEvent**, **SecretRotatedEvent**, **PluginLoadedEvent** |
| **Plugin errors** | Optional: plugin throw or load failure for observability. | **PluginErrorEvent** (optional) |

Execution events are **run-scoped** and **consumer-facing** (UI, APIs). Connection and lifecycle events are **operational** (observability, cache invalidation). All must **never contain resolved secret values**; see [secret-architecture](secret-architecture.md) (masking in logs, events, UI).

---

## Event Communication Overview

```
  Producers                          Transport / Sink                    Consumers
  ─────────                          ─────────────────                  ─────────
  ExecutionEventsFeature    ──►     ExecutionEventSink                 Chat UI (getEvents(runId))
  (per node: planner/tool/model)      (e.g. InMemoryExecutionEventSink)  Logging
  workflow.started / completed        keyed by runId                      Optional: SSE, webhook, backend

  ConnectionRuntimeManager   ──►     In-process listeners / log         Cache invalidator (ConnectionKey)
  ConnectionResolver                 (no secret values)                 Metrics / APM
  ──► ConnectionResolvedEvent
  ──► RuntimeCreatedEvent
  ──► ConnectionErrorEvent

  Config / secret system      ──►     In-process event bus or callback   ConnectionRuntimeManager (invalidate)
  ──► ConnectionUpdatedEvent
  ──► SecretRotatedEvent

  Plugin loader               ──►     Log / optional sink                Registry; optional PluginLoadedEvent
  ──► PluginLoadedEvent (optional)
  ──► PluginErrorEvent (optional)
```

- **Execution events** use a **run-scoped sink** (ExecutionEventSink) so the chat UI (or any API) can read events by **runId**.
- **Connection and lifecycle events** are typically **in-process** (listeners or callbacks) so the Connection Runtime Manager can invalidate caches; they can also be logged or forwarded to metrics.
- **No resolved secrets** appear in any event payload; use placeholders or redaction. See [secret-architecture](secret-architecture.md) §Masking.

---

## Real-time event flow

This flow powers **chat UI**, **execution timeline**, **step logs**, and **live progress**. Without documenting it, contributors struggle with UI integration.

```
Execution Engine
       │
       ▼
Execution Events
(NodeStart, NodeEnd, Error)
       │
       ▼
Event Bus / Sink
       │
       ├── UI Stream (WebSocket / SSE / getEvents(runId))
       ├── Run Ledger
       ├── Metrics
       ├── Logs
       └── Debug Timeline
```

- **Execution Engine** (via ExecutionEventsFeature) emits semantic events (e.g. planner.started, tool.completed, workflow.completed) keyed by **runId**.
- **Event Bus / Sink** — Today: **ExecutionEventSink** (e.g. InMemoryExecutionEventSink) stores events by runId; the sink is also logged. Consumers: **Chat UI** (poll or API with runId), **Run Ledger** (when ledger is enabled), **Metrics** (if forwarded), **Logs** (every emit), **Debug Timeline** (same events, different view).
- **UI integration:** Backend or UI calls `getEvents(runId)` (in-process) or an API that reads from the same sink (out-of-process). For real-time streaming, add a **WebSocket or SSE** endpoint that pushes new events for a runId as they are emitted. See [execution-events](../execution-events.md).

---

## Execution Events

Execution events are **semantic steps** emitted during a pipeline run so the chat UI (and other consumers) can show readable, debuggable progress.

### Producer

- **ExecutionEventsFeature** (PRE_FINALLY) — Emits events when a sink is registered (e.g. **ExecutionEventSink**). Auto-attached so no pipeline config is required. Emits:
  - **workflow.started** / **workflow.completed** / **workflow.failed** — Run boundaries.
  - **planner.started** / **planner.completed** — Planner node.
  - **tool.started** / **tool.completed** — Tool/plugin node.
  - **model.started** / **model.completed** — Model node (inferred from plugin id).

See [execution-events](../execution-events.md) for event types, JSON shape, and UI representation.

### Sink and keying

- **ExecutionEventSink** — Interface (e.g. in `olo-run-ledger`). Default implementation **InMemoryExecutionEventSink** stores events by **runId** and logs each emit.
- **runId** — Comes from workflow input (`context.runId`) when provided; otherwise generated. Same runId is used for the whole run (tree or per-node) so the UI can poll or subscribe by runId.

### Consumers

- **Chat UI** — Calls `getEvents(runId)` (in-process) or an API that reads from the same sink (out-of-process).
- **Logging** — Every emit is logged (runId, eventType, message) for traceability.
- **Future** — SSE stream, webhook push, or message bus can consume from the same sink or a secondary forwarder.

### Contract (summary)

| Field | Description |
|-------|-------------|
| **eventType** | e.g. `planner.completed`, `model.completed`, `workflow.started`. |
| **label** | Human-readable short label. |
| **payload** | Optional map: **message**, **pluginId**, **queueName**, **error** (no secrets). |
| **timestampMillis** | Epoch millis. |
| **nodeId** | Optional; set for node-scoped events. |

Payload and logs must **never** contain resolved secret values; use masking. See [secret-architecture](secret-architecture.md).

---

## Connection and Plugin Observability Events

When the Connection Manager is used, resolution and runtime creation can emit events for debugging and monitoring.

### Event shapes (minimal)

| Event | Typical fields | When emitted |
|-------|-----------------|--------------|
| **ConnectionResolvedEvent** | tenantId, connectionName, plugin, durationMs | After ConnectionResolver returns ResolvedConnection. |
| **RuntimeCreatedEvent** | tenantId, connectionName, plugin, runtimeType | After createRuntime and cache put. |
| **ConnectionErrorEvent** | tenantId, connectionName, plugin, errorType, message (no secret values) | On resolution or runtime creation failure. |

Defined in [connection-manager-design](connection-manager-design.md) §Observability. Emitted by **ConnectionRuntimeManager** (and optionally ConnectionResolver). Consumers: log, metrics/APM, and optionally the same execution event sink or a dedicated connection-events stream.

---

## Lifecycle and Cache Invalidation Events

These events drive **cache invalidation** and config refresh so the worker does not use stale connections or secrets.

### ConnectionUpdatedEvent

- **Producer** — Config/connection store or admin API when a connection is updated (e.g. version bump).
- **Consumer** — **ConnectionRuntimeManager** invalidates the cache entry for the affected **ConnectionKey** (tenantId, connectionName; optionally version). Next request creates a fresh runtime with the new config.
- **Purpose** — Hot reload of connection config without process restart.

### SecretRotatedEvent

- **Producer** — Secret provider or vault integration when a secret is rotated (e.g. secret id or logical ref + tenant).
- **Consumer** — **ConnectionRuntimeManager** (or SecretResolver) invalidates **only runtimes that reference that secret** (e.g. via runtime → secretRefs mapping). See [connection-manager-design](connection-manager-design.md) and [secret-architecture](secret-architecture.md).
- **Purpose** — Avoid using stale credentials after rotation.

### PluginLoadedEvent (optional)

- **Producer** — Plugin loader after a plugin is successfully registered.
- **Consumer** — Log; optional dashboard or registry UI.
- **Purpose** — Visibility into which plugins are loaded at startup (or hot reload).

Event payloads must **never** contain resolved secrets; use identifiers only.

---

## Transport and Sinks

| Mechanism | Used for | Notes |
|-----------|----------|--------|
| **In-process sink** | Execution events | ExecutionEventSink (e.g. InMemoryExecutionEventSink) keyed by runId. |
| **In-process listeners / callbacks** | Connection and lifecycle events | ConnectionRuntimeManager subscribes to ConnectionUpdatedEvent, SecretRotatedEvent for cache invalidation. |
| **Logging** | All events | Every execution event and optionally connection/plugin events are logged (with masking). |
| **Future: SSE / webhook / bus** | Execution or connection events | Worker could push to backend (e.g. callbackBaseUrl) or publish to a message bus; event shapes stay the same. |

No Temporal workflow history is required for event communication; events are produced by the worker (activities/features) and consumed by sinks or listeners within the same process or via an API that reads from those sinks.

---

## Security and Masking

- **Secrets** — Resolved secret values must **never** appear in any event payload, log line, or UI. Use value-based masking (see [secret-architecture](secret-architecture.md)): track resolved values during resolution and replace exact matches with a placeholder (e.g. `********`) before writing to log, event, or UI.
- **Errors** — **ConnectionErrorEvent** and **PluginErrorEvent** must not embed resolved secrets in **message** or **payload**; use **SecretSafeExceptionFormatter** or equivalent when serializing errors.

---

## Glossary

| Term | Meaning |
|------|---------|
| **Execution event** | A semantic step (e.g. planner.completed, model.completed) emitted during a run; keyed by runId; consumed by chat UI and logs. See [execution-events](../execution-events.md). |
| **ExecutionEventSink** | Interface to store and retrieve execution events by runId (e.g. InMemoryExecutionEventSink). |
| **ConnectionResolvedEvent** | Observability event: connection was resolved (tenantId, connectionName, plugin, durationMs). |
| **RuntimeCreatedEvent** | Observability event: runtime was created and cached (tenantId, connectionName, plugin, runtimeType). |
| **ConnectionErrorEvent** | Observability event: resolution or runtime creation failed (no secret values). |
| **ConnectionUpdatedEvent** | Lifecycle event: connection config was updated; triggers cache invalidation for that ConnectionKey. |
| **SecretRotatedEvent** | Lifecycle event: a secret was rotated; triggers invalidation of runtimes that use that secret. |

---

## Summary

| Aspect | Design |
|--------|--------|
| **Execution events** | ExecutionEventsFeature → ExecutionEventSink (runId-keyed). Types: workflow, planner, tool, model. For chat UI and logging. See [execution-events](../execution-events.md). |
| **Connection observability** | ConnectionResolvedEvent, RuntimeCreatedEvent, ConnectionErrorEvent from ConnectionRuntimeManager; no secrets in payloads. |
| **Cache invalidation** | ConnectionUpdatedEvent, SecretRotatedEvent drive ConnectionRuntimeManager cache invalidation. |
| **Transport** | In-process sinks and listeners today; logging for all; optional SSE/webhook/bus later. |
| **Security** | No resolved secrets in any event; value-based masking for logs and events. See [secret-architecture](secret-architecture.md). |

This gives Olo a clear **event communication architecture**: run-scoped execution events for UIs, connection and plugin events for observability, and lifecycle events for cache invalidation, with consistent rules for transport and security.

---

## Related architecture docs

| Document | Description |
|----------|-------------|
| [execution-events](../execution-events.md) | Execution event types, JSON shape, runId, chat UI consumption. |
| [feature-design](feature-design.md) | ExecutionEventsFeature, feature phases, NodeExecutionContext. |
| [connection-manager-design](connection-manager-design.md) | Connection observability events, cache invalidation (ConnectionUpdatedEvent, SecretRotatedEvent). |
| [plugin-design](plugin-design.md) | Plugin failure behavior, optional PluginErrorEvent. |
| [secret-architecture](secret-architecture.md) | Masking in logs, events, UI; no secrets in event payloads. |

For the full list of architecture docs, see [plugin-design](plugin-design.md) §Related architecture docs.
