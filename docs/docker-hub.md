# Docker Hub — description and summary

Use the text below on the [Docker Hub repository page](https://hub.docker.com/) for **olo-worker** (Settings → Repository description / Full description).

---

## Summary (short description)

Use this in the **Short description** / **Summary** field (e.g. 100-character limit):

```
Temporal worker for OLO: runs pipeline execution trees, planner, plugins, and ledger. Java 17, Alpine.
```

Or shorter:

```
OLO Temporal worker — pipeline execution, planner, plugins. Java 17 Alpine.
```

---

## Full description (Markdown)

Paste the following into the **Full description** field. Docker Hub supports Markdown.

```markdown
# OLO Worker

Temporal worker for the **Open LLM Orchestrator (OLO)** platform. Polls task queues, runs pipeline execution trees (planner, tools, models), and optionally persists run and node data to the ledger.

## Features

- **Temporal integration** — Connects to a Temporal server and executes workflows (e.g. chat, RAG, custom pipelines).
- **Pipeline execution** — Runs configurable execution trees: SEQUENCE, PLUGIN, PLANNER, branches, and dynamic steps.
- **Planner** — LLM-driven planning; expands into tool/model steps at runtime.
- **Plugins** — Model executors (Ollama, LiteLLM, etc.), tools, reducers; tenant-scoped registry.
- **Run ledger** — Optional persistence of run and node records (PostgreSQL) when `OLO_RUN_LEDGER=true`.
- **Execution events** — Semantic step events (planner, tool, model) for chat UI and observability.

## Image

- **Base:** Eclipse Temurin 17 JRE (Alpine).
- **Entrypoint:** `/app/bin/olo-worker`.
- **Working directory:** `/app`. Pipeline config is under `/app/config`.

## Requirements

- A **Temporal server** (worker connects to it; not included in this image).
- Optional: Redis (session/config), PostgreSQL (run ledger), according to your OLO setup.

## Environment variables (examples)

| Variable | Description | Default |
|----------|-------------|---------|
| `OLO_QUEUE` | Comma-separated task queue names | `olo-chat-queue-ollama` |
| `OLO_TENANT_IDS` | Comma-separated tenant IDs | `default` |
| `OLO_DEFAULT_TENANT_ID` | Default tenant | `default` |
| `OLO_RUN_LEDGER` | Enable run/node ledger persistence | `false` |
| `OLO_IS_DEBUG_ENABLED` | Enable debug pipelines (e.g. `-debug` queues) | `false` |

See the [OLO Worker documentation](https://github.com/OpenLLMOrchestrator/olo-worker/tree/main/docs) for full configuration (Redis, DB, pipeline config, etc.).

## Usage

Run the worker and point it at your Temporal server (e.g. host or another container):

```bash
docker run -e OLO_QUEUE=olo-chat-queue-ollama \
  -e TEMPORAL_HOST=host.docker.internal:7233 \
  ghcr.io/openllmorchestrator/olo-worker:latest
```

Or with Docker Hub image:

```bash
docker run -e OLO_QUEUE=olo-chat-queue-ollama \
  -e TEMPORAL_HOST=host.docker.internal:7233 \
  <your-dockerhub-username>/olo-worker:latest
```

## Tags

- `latest` — Latest build from the default branch.
- `main` — Build from the `main` branch.
- `<sha>` — Build from a specific Git commit (e.g. `d926cbb`).

## Source

- **GitHub:** [OpenLLMOrchestrator/olo-worker](https://github.com/OpenLLMOrchestrator/olo-worker)
- **Docs:** [docs/](https://github.com/OpenLLMOrchestrator/olo-worker/tree/main/docs)
```

---

## Where to paste

1. **Docker Hub** → Your repository → **Settings** (or **Edit**).
2. **Short description:** Paste the **Summary** text.
3. **Full description:** Paste the **Full description** Markdown (the block above, without the outer code fence if the UI expects plain Markdown).

Adjust the image name (`ghcr.io/openllmorchestrator/olo-worker`, `<your-dockerhub-username>/olo-worker`) to match your registry and username.
