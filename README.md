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
| `OLO_SESSION_DATA` | Session key prefix; workflow input stored at `<prefix><transactionId>:USERINPUT` | `olo:kernel:sessions:` |

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
- **`olo-worker-configuration`** — separate module with `com.olo.config.OloConfig`; reads all `OLO_*` env and exposes `getTaskQueues()`, cache/DB/Temporal settings. No Temporal dependency.
- **`olo-worker`** — application module; depends on `olo-worker-configuration` and Temporal SDK. `OloWorkerApplication` builds the Temporal client and workers for each task queue; register workflows/activities there.

## License

Apache-2.0
