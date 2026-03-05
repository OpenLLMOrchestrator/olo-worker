# OLO Temporal Worker

Java Gradle application that runs a Temporal worker with task queues and infrastructure config read from environment variables.

## What is OLO?

**OLO is AI runtime infrastructure, not an agent library.** It sits below agent frameworks and above Temporal and infrastructure:

```
Applications
(Chat, Agents, AI Apps)
        │
        ▼
Agent Frameworks
(LangChain, CrewAI, etc.)
        │
        ▼
OLO Runtime
 ├ Execution Kernel
 ├ Feature System
 ├ Plugin System
 ├ Connection Runtime
 ├ Secret System
 └ Event System
        │
        ▼
Temporal Workflow Engine
        │
        ▼
Infrastructure
(DB, Redis, Vault, APIs)
```

Agent frameworks (LangChain, CrewAI, etc.) can run on top of OLO. OLO provides the **Execution Kernel** (declarative pipelines, Execution Tree), **Feature System** (logging, quota, metrics, ledger), **Plugin System** (LLM, tools, DB), **Connection Runtime**, **Secret System**, and **Event System**—all on top of Temporal and your infrastructure. See [Olo Runtime Architecture](#olo-runtime-architecture) and [docs/](docs/README.md) for details.

## Requirements

- Java 17+
- [Temporal Server](https://docs.temporal.io/self-hosted) (e.g. `temporal server start-dev` for local)

## Environment variables

| Variable | Description | Example |
|----------|-------------|---------|
| `OLO_QUEUE` | Comma-separated Temporal task queue names | `olo-chat-queue-ollama,olo-rag-queue-openai` |
| `OLO_IS_DEBUG_ENABLED` | If `true`, also poll `&lt;queue&gt;-debug` for each queue (e.g. olo-chat-queue-ollama-debug) | `true` |
| `OLO_CACHE_HOST` | Cache host (e.g. Redis) | `localhost` |
| `OLO_CACHE_PORT` | Cache port | `6379` |
| `OLO_DB_HOST` | Database host | `localhost` |
| `OLO_DB_PORT` | Database port | `5432` |
| `OLO_DB_NAME` | Database name (default: `temporal`) | `temporal` |
| `OLO_RUN_LEDGER` | When `true`, persist run/node records to DB (olo_run, olo_run_node, olo_config). **Default: true** when unset. Set to `false` to disable. | `true` |
| `OLO_TENANT_IDS` | Comma-separated tenant ids when Redis `olo:tenants` is not set | `default` |
| `OLO_DEFAULT_TENANT_ID` | Tenant id when workflow `context.tenantId` is missing or blank | `2a2a91fb-f5b4-4cf0-b917-524d242b2e3d` |
| `OLO_SESSION_DATA` | Session key prefix; workflow input stored at `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT` | `<tenant>:olo:kernel:sessions:` |

Temporal connection (target and namespace) is taken from **pipeline configuration** (`executionDefaults.temporal.target` and `executionDefaults.temporal.namespace` in your pipeline config JSON), not from environment variables.

With `OLO_QUEUE=olo-chat-queue-ollama,olo-rag-queue-openai` and `OLO_IS_DEBUG_ENABLED=true`, the worker registers:

- `olo-chat-queue-ollama`
- `olo-rag-queue-openai`
- `olo-chat-queue-ollama-debug`
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
$env:OLO_QUEUE = "olo-chat-queue-ollama,olo-rag-queue-openai"
$env:OLO_IS_DEBUG_ENABLED = "true"
$env:OLO_CACHE_HOST = "localhost"
$env:OLO_CACHE_PORT = "6379"
$env:OLO_DB_HOST = "localhost"
$env:OLO_DB_PORT = "5432"
.\gradlew :olo-worker:run
```

With env vars (Linux/macOS):

```bash
export OLO_QUEUE="olo-chat-queue-ollama,olo-rag-queue-openai"
export OLO_IS_DEBUG_ENABLED=true
export OLO_CACHE_HOST=localhost
export OLO_CACHE_PORT=6379
export OLO_DB_HOST=localhost
export OLO_DB_PORT=5432
./gradlew :olo-worker:run
```

## Docker

Build the image (from the repo root):

```bash
docker build -t olo-worker:latest .
```

Run the worker (pass env vars and ensure the container can reach Temporal, Redis, DB as needed):

```bash
docker run --rm -e OLO_QUEUE=olo-chat-queue-ollama -e OLO_IS_DEBUG_ENABLED=true \
  -e OLO_CACHE_HOST=host.docker.internal -e OLO_CACHE_PORT=6379 \
  -e OLO_TENANT_IDS=default \
  olo-worker:latest
```

Pipeline config is included in the image under `/app/config`. Temporal target/namespace come from that config. Use `-v` to override config or env vars as needed.

**CI:** Pushes to `main`/`master` and published releases trigger [GitHub Actions](.github/workflows/docker-publish.yml) to build and push the image to [GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry). Image: `ghcr.io/<owner>/<repo>:latest` (replace `<owner>/<repo>` with your GitHub org/repo). For private images, use a PAT with `read:packages` when pulling.

## Project layout (multi-module)

- **Root** — parent Gradle project; no application code.
- **olo-annotations** — `@OloFeature`, `@OloPlugin`, `@OloUiComponent`; ResourceCleanup; annotation processor for feature/plugin/UI metadata.
- **olo-worker-protocol** — Contracts only (no impl): `BootstrapContext`, `WorkerBootstrapContext`, `BootstrapContributor`; `PluginExecutor`, `PluginExecutorFactory`. Lets plugins, tools, and features depend on the contract without pulling in bootstrap or plugin implementation.
- **olo-worker-configuration** — `OloConfig` from env (queues, cache, DB, session/config prefixes, **OLO_TENANT_IDS**, **OLO_DEFAULT_TENANT_ID**); `OloSessionCache`; TenantConfig / TenantConfigRegistry.
- **olo-worker-input** — `WorkflowInput`, `InputItem`, `Context`, `Routing`, `Metadata`; storage modes (LOCAL, CACHE, FILE).
- **olo-worker-execution-tree** — Pipeline model, `ExecutionTreeNode`, `GlobalConfigurationContext`, configuration loader (Redis/DB/file).
- **olo-worker-execution-context** — `LocalContext`, `ExecutionConfigSnapshot` (tenantId, queueName, config, optional runId).
- **olo-run-ledger** — Run ledger (optional): `LedgerStore`, `JdbcLedgerStore` (olo_run, olo_run_node, olo_config; UUID/TIMESTAMPTZ); RunLedger, RunLevelLedgerFeature, NodeLedgerFeature.
- **olo-worker-features** — FeatureRegistry, PreNodeCall/FinallyCall/PreFinallyCall, FeatureAttachmentResolver, NodeExecutionContext.
- **olo-feature-debug** — DebuggerFeature (pre/post logging when queue ends with `-debug`).
- **olo-feature-quota** — QuotaFeature (per-tenant soft/hard limits from Redis activeWorkflows).
- **olo-feature-metrics** — MetricsFeature (node execution counters).
- **olo-worker-plugin** — ModelExecutorPlugin, PluginRegistry (tenant-scoped); `PluginExecutorFactory` impl; PluginManager, PluginProvider.
- **olo-plugin-ollama** — Ollama model-executor plugin.
- **olo-worker-bootstrap** — `OloBootstrap.initialize()` → `BootstrapContext`; `OloBootstrap.initializeWorker()` → `WorkerBootstrapContext` (adds runLedger, sessionCache, pluginExecutorFactory, runResourceCleanup). Loads tenant list and pipeline config; runs **BootstrapContributor**s (e.g. planner); registers plugins/tools and features; returns context so the worker only starts Temporal.
- **olo-worker** — Application: `OloWorkerApplication` calls `OloBootstrap.initializeWorker()` and uses `WorkerBootstrapContext`; Temporal client and workers per task queue; **OloKernelWorkflow** / **OloKernelWorkflowImpl**; **OloKernelActivitiesImpl** and **ExecuteNodeDynamicActivity** (one activity per leaf when tree is linear); ExecutionEngine, NodeExecutor, PluginInvoker (uses protocol `PluginExecutor`), ResultMapper. Shutdown calls `ctx.runResourceCleanup()`.

Additional modules (see [docs/architecture-and-features.md](docs/architecture-and-features.md)): olo-worker-tools, olo-join-reducer, olo-internal-plugins, olo-internal-tools, olo-planner, olo-planner-a, and various **olo-plugin-*** / **olo-tool-*** modules.

See [docs/architecture-and-features.md](docs/architecture-and-features.md) for the full module map and data flow.

## Olo Runtime Architecture

A simplified view of how a pipeline run flows:

```
Pipeline Config  →  Execution Tree  →  Execution Engine  →  Plugins
```

From a pipeline step to the external API (resource resolution):

```
Pipeline Step
     │
     ▼
ctx.model("openai-prod")
     │
     ▼
ResourceRuntimeManager
     │
     ▼
Runtime Cache
     │
     ▼
Plugin.createRuntime()
     │
     ▼
OpenAI API
```

The **Execution Tree** is the declarative program (SEQUENCE, IF, PLUGIN, SWITCH, …). The **Execution Engine** interprets it. **Plugins** provide capabilities (LLM, tools, DB); **Features** wrap every node (logging, quota, metrics, ledger). Full stack:

```
                     ┌───────────────────────────────────────┐
                     │               USER / API               │
                     │  Chat UI • REST API • SDK • CLI       │
                     └───────────────────────────────────────┘
                                      │
                                      ▼
                     ┌───────────────────────────────────────┐
                     │            OLO WORKFLOW                │
                     │  Temporal Workflow Orchestrator       │
                     │                                       │
                     │  • start pipeline run                 │
                     │  • manage retries / timeouts          │
                     │  • schedule activities                │
                     └───────────────────────────────────────┘
                                      │
                                      ▼
                     ┌───────────────────────────────────────┐
                     │            EXECUTION ENGINE            │
                     │                                       │
                     │  ExecutionEngine                      │
                     │  NodeExecutor                        │
                     │  ResultMapper                        │
                     │                                       │
                     │  Interprets the execution tree        │
                     └───────────────────────────────────────┘
                                      │
                                      ▼
              ┌───────────────────────────────────────────────┐
              │              EXECUTION TREE                    │
              │                                               │
              │  Declarative pipeline program                 │
              │                                               │
              │  SEQUENCE → IF → PLUGIN → SWITCH → JOIN      │
              │                                               │
              │  Each node: id, type, params, feature hooks   │
              └───────────────────────────────────────────────┘
                        │                       │
                        ▼                       ▼
        ┌──────────────────────────┐   ┌──────────────────────────┐
        │        FEATURES           │   │         VARIABLES         │
        │  Logging • Quotas •       │   │  VariableEngine           │
        │  Metrics • Ledger •       │   │  IN / INTERNAL / OUT       │
        │  Execution Events         │   │  inputMappings /           │
        │  (run around every node)  │   │  outputMappings /         │
        └──────────────────────────┘   │  resultMapping            │
                        │              └──────────────────────────┘
                        ▼
             ┌──────────────────────────────┐
             │           PLUGINS            │
             │  LLM • tools • databases     │
             │  pluginRef → PluginRegistry  │
             └──────────────────────────────┘
                        │
                        ▼
           ┌──────────────────────────────────┐
           │    TENANT INFRASTRUCTURE         │
           │  Connection Manager • Secrets   │
           │  Config snapshot • Queue routing │
           └──────────────────────────────────┘
                        │
                        ▼
             ┌──────────────────────────────┐
             │          RUN LEDGER          │
             │  run / node / events         │
             └──────────────────────────────┘
```

**Three concepts:** (1) **Execution Tree = the program** — the pipeline config is a declarative program; the Execution Engine is the interpreter. (2) **Plugins = capabilities** — the tree decides *what* runs; plugins do the *work*. (3) **Features = cross-cutting behavior** — they wrap node execution (pre → node → post) for logging, quota, metrics, ledger, and events without polluting pipeline logic. For details see [docs/](docs/README.md) and [architecture-and-features](docs/arcitecture/architecture-and-features.md).

## License

Apache-2.0
