# Pipeline configuration files

Configuration files in this folder are **pipeline configurations** (see [Pipeline configuration: how to use](../docs/pipeline-configuration-how-to.md)).

## Naming convention

- **`default.json`** – Fallback configuration when no queue-specific file is found.
- **`<task-queue-name>.json`** – Configuration for a specific Temporal task queue. The filename must match the task queue name (e.g. from `OLO_QUEUE`).

## Examples

| File | Use when |
|------|----------|
| `default.json` | No queue-specific config or as fallback. |
| `olo-chat-queue-oolama.json` | Task queue is `olo-chat-queue-oolama`. |
| `olo-rag-queue-openai.json` | Task queue is `olo-rag-queue-openai`. |

Load the config for the current queue by name (e.g. `config/olo-chat-queue-oolama.json`), or use `config/default.json` when the queue has no dedicated file.
