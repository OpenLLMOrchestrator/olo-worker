# olo-plugin-ollama

Ollama model-executor plugin for the OLO worker. Calls the Ollama API to get AI model responses and registers as a `ModelExecutorPlugin` (e.g. under id `GPT4_EXECUTOR` for use with `olo-chat-queue-ollama`).

## Contract

- **Input:** `prompt` (String).
- **Output:** `responseText` (String).
- Uses `POST http://baseUrl/api/chat` with a single user message; `stream: false`.

## Configuration

- **OLLAMA_BASE_URL** – Ollama base URL (default `http://localhost:11434`).
- **OLLAMA_MODEL** – Model name (default `llama3.2`).

## Registration

The olo-worker application registers this plugin at startup under id `GPT4_EXECUTOR` so pipeline configs that reference `pluginRef: "GPT4_EXECUTOR"` (e.g. `config/olo-chat-queue-ollama.json`) use Ollama.

Programmatic registration:

```java
OllamaModelExecutorPlugin.registerOllamaPlugin("GPT4_EXECUTOR", "http://localhost:11434", "llama3.2");
// or
OllamaModelExecutorPlugin plugin = new OllamaModelExecutorPlugin(baseUrl, model);
plugin.register("GPT4_EXECUTOR");
```

## Requirements

- Ollama running (e.g. `ollama serve`) with the chosen model pulled (e.g. `ollama pull llama3.2`).

## Local run vs Docker

- **Local:** Default `OLLAMA_BASE_URL` is `http://localhost:11434`. Have Ollama on the host and pull the model: `ollama pull llama3.2` (or set `OLLAMA_MODEL` to a model you have).
- **Docker/stack:** Set `OLLAMA_BASE_URL=http://ollama:11434` so the worker can reach the Ollama service.
