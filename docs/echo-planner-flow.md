# Echo via Planner – end-to-end flow

Simple flow: user asks to **echo** something (e.g. "echo the capital of India"); the **planner** (LLM) produces a single step using **ECHO_TOOL**; the tool returns `"ECHO: " + input`.

## Components

- **ECHO_TOOL** – Plugin/tool that returns `"ECHO: " + request.getInput()` (accepts `prompt` or `input`, outputs `responseText`).
- **Pipeline** `olo-echo-planner` – SEQUENCE → PLANNER node. Planner uses `GPT4_EXECUTOR` (Ollama) to turn the user query into a JSON array of steps; the default subtree builder runs each step as a PLUGIN (toolId = pluginRef).
- **Config** – `config/olo-echo-planner.json` defines the pipeline, scope (ECHO_TOOL, GPT4_EXECUTOR), and result mapping from the first step’s response to the workflow answer.

## Flow

1. Workflow input: `userQuery` = e.g. `"echo the capital of India"`.
2. PLANNER node: Injects `userQuery` into the planner prompt template, calls Ollama; model returns something like:
   ```json
   [{"toolId": "ECHO_TOOL", "input": {"prompt": "the capital of India"}}]
   ```
3. Subtree builder creates one PLUGIN node: pluginRef = `ECHO_TOOL`, input mapping `prompt` ← variable (injected prompt text).
4. Engine runs that node: ECHO_TOOL receives `prompt: "the capital of India"` and returns `responseText: "ECHO: the capital of India"`.
5. That value is written to `__planner_step_0_response` and mapped to the workflow output `answer`.

## How to run

1. Include the echo tool and queue in the worker:
   - `olo-tool-echo` is registered in `InternalTools`; add `olo-echo-planner` to `OLO_QUEUE` in `.env` (see `env.example`).
2. Load the pipeline config for the queue `olo-echo-planner` (e.g. from Redis or bootstrap so `LocalContext.forQueue(..., "olo-echo-planner", ...)` returns the config from `config/olo-echo-planner.json`).
3. Start a workflow on task queue `olo-echo-planner` (or `olo-echo-planner-debug`) with input:
   ```json
   { "inputs": [{ "name": "userQuery", "value": "echo the capital of India" }], ... }
   ```
4. Expect workflow result (answer): `ECHO: the capital of India` (or whatever text the planner put in the step’s `prompt`).

## Dependencies

- Ollama running (for the planner model). ECHO_TOOL has no external calls.
