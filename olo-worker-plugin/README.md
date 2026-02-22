# olo-worker-plugin

Plugin contracts and registry for the OLO worker. Plugins implement contracts (e.g. `ModelExecutorPlugin`) and register with `PluginRegistry` by id so the worker can resolve `pluginRef` from execution tree nodes and invoke them.

## Contracts

- **ContractType** – Constants: `MODEL_EXECUTOR`, `EMBEDDING` (aligned with pipeline scope `contractType`).
- **ModelExecutorPlugin** – `execute(Map<String, Object> inputs) → Map<String, Object> outputs` (e.g. `prompt` → `responseText`).
- **PluginRegistry** – Singleton: `registerModelExecutor(id, plugin)`, `get(id)`, `getModelExecutor(id)`.

## Binding with olo-worker

The worker (or execution layer) looks up a plugin by the node’s `pluginRef`, then builds inputs from the node’s `inputMappings` (variable → pluginParameter) and calls the plugin’s `execute(inputs)`, then maps outputs back via `outputMappings`.

## Usage

```java
PluginRegistry.getInstance().registerModelExecutor("GPT4_EXECUTOR", myModelExecutorPlugin);
ModelExecutorPlugin plugin = PluginRegistry.getInstance().getModelExecutor("GPT4_EXECUTOR");
Map<String, Object> out = plugin.execute(Map.of("prompt", "Hello"));
```
