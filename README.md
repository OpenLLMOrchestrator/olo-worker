# OLO Temporal Worker

Java Gradle application that runs a Temporal worker with task queues and infrastructure config read from environment variables.

## Requirements

- Java 17+
- [Temporal Server](https://docs.temporal.io/self-hosted) (e.g. `temporal server start-dev` for local)

## Environment variables

| Variable | Description | Example |
|----------|-------------|---------|
| `OLO_QUEUE` | Comma-separated Temporal task queue names | `olo-chat-queue-oolama,olo-rag-queue-openai` |
| `OLO_IS_DEBUG_ENABLED` | If `true`, also poll `&lt;queue&gt;-debug` for each queue (e.g. olo-chat-queue-oolama-debug) | `true` |
| `OLO_CACHE_HOST` | Cache host (e.g. Redis) | `localhost` |
| `OLO_CACHE_PORT` | Cache port | `6379` |
| `OLO_DB_HOST` | Database host | `localhost` |
| `OLO_DB_PORT` | Database port | `5432` |
| `OLO_DB_NAME` | Database name (default: `temporal`) | `temporal` |
| `OLO_RUN_LEDGER` | When `true`, persist run/node records to DB (olo_run, olo_run_node, olo_config). Default true in dev. | `true` |
| `OLO_TENANT_IDS` | Comma-separated tenant ids when Redis `olo:tenants` is not set | `default` |
| `OLO_DEFAULT_TENANT_ID` | Tenant id when workflow `context.tenantId` is missing or blank | `2a2a91fb-f5b4-4cf0-b917-524d242b2e3d` |
| `OLO_SESSION_DATA` | Session key prefix; workflow input stored at `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT` | `<tenant>:olo:kernel:sessions:` |

Temporal connection (target and namespace) is taken from **pipeline configuration** (`executionDefaults.temporal.target` and `executionDefaults.temporal.namespace` in your pipeline config JSON), not from environment variables.

With `OLO_QUEUE=olo-chat-queue-oolama,olo-rag-queue-openai` and `OLO_IS_DEBUG_ENABLED=true`, the worker registers:

- `olo-chat-queue-oolama`
- `olo-rag-queue-openai`
- `olo-chat-queue-oolama-debug`
- `olo-rag-queue-openai-debug`

## Build and run

Generate the Gradle wrapper (once, if not present):

```bash
gradle wrapper
```

Then (run the **worker** module):

```bash
# Windows
.\gradlew :olo-worker:run

# Linux/macOS
./gradlew :olo-worker:run
```

With env vars (Windows PowerShell):

```powershell
$env:OLO_QUEUE = "olo-chat-queue-oolama,olo-rag-queue-openai"
$env:OLO_IS_DEBUG_ENABLED = "true"
$env:OLO_CACHE_HOST = "localhost"
$env:OLO_CACHE_PORT = "6379"
$env:OLO_DB_HOST = "localhost"
$env:OLO_DB_PORT = "5432"
.\gradlew :olo-worker:run
```

With env vars (Linux/macOS):

```bash
export OLO_QUEUE="olo-chat-queue-oolama,olo-rag-queue-openai"
export OLO_IS_DEBUG_ENABLED=true
export OLO_CACHE_HOST=localhost
export OLO_CACHE_PORT=6379
export OLO_DB_HOST=localhost
export OLO_DB_PORT=5432
./gradlew :olo-worker:run
```

## Project layout (multi-module)

- **Root** — parent Gradle project; no application code.
- **olo-annotations** — `@OloFeature`, `@OloPlugin`, `@OloUiComponent`; ResourceCleanup; annotation processor for feature/plugin/UI metadata.
- **olo-worker-configuration** — `OloConfig` from env (queues, cache, DB, session/config prefixes, **OLO_TENANT_IDS**, **OLO_DEFAULT_TENANT_ID**); `OloSessionCache`; TenantConfig / TenantConfigRegistry.
- **olo-worker-input** — `WorkflowInput`, `InputItem`, `Context`, `Routing`, `Metadata`; storage modes (LOCAL, CACHE, FILE).
- **olo-worker-execution-tree** — Pipeline model, `ExecutionTreeNode`, `GlobalConfigurationContext`, configuration loader (Redis/DB/file).
- **olo-worker-execution-context** — `LocalContext`, `ExecutionConfigSnapshot` (tenantId, queueName, config, optional runId).
- **olo-run-ledger** — Run ledger (optional): `LedgerStore`, `JdbcLedgerStore` (olo_run, olo_run_node, olo_config; UUID/TIMESTAMPTZ); RunLedger, RunLevelLedgerFeature, NodeLedgerFeature.
- **olo-worker-features** — FeatureRegistry, PreNodeCall/FinallyCall/PreFinallyCall, FeatureAttachmentResolver, NodeExecutionContext.
- **olo-feature-debug** — DebuggerFeature (pre/post logging when queue ends with `-debug`).
- **olo-feature-quota** — QuotaFeature (per-tenant soft/hard limits from Redis activeWorkflows).
- **olo-feature-metrics** — MetricsFeature (node execution counters).
- **olo-worker-plugin** — ModelExecutorPlugin, PluginRegistry (tenant-scoped).
- **olo-plugin-ollama** — Ollama model-executor plugin.
- **olo-worker-bootstrap** — `OloBootstrap.initialize()`: tenant list (Redis olo:tenants or OLO_TENANT_IDS), GlobalConfigurationContext, BootstrapContext.
- **olo-worker** — Application: `OloWorkerApplication`; Temporal client and workers per task queue; **OloKernelWorkflow** / **OloKernelWorkflowImpl**; OloKernelActivitiesImpl; ExecutionEngine, NodeExecutor, PluginInvoker, ResultMapper.

See [docs/architecture-and-features.md](docs/architecture-and-features.md) for the full module map and data flow.

## License

Apache-2.0
