# Plugin / Feature / Configuration Annotations Module

This document defines the role of the **annotation module** (today `olo-annotations`) in the Olo architecture, and how it should be used for plugins, features, and configuration/UX metadata.

The goal is to keep the **kernel and runtime free of heavy reflection and scanning**, while still giving:

- Rich metadata for **plugins** and **features**.
- Generated JSON descriptors for **UI**, **canvas**, and **configuration editors**.
- A clean, compile-time-only dependency for plugin/feature authors.

---

## 1. Module Purpose

**Module:** `olo-annotations` (conceptually: `olo-config-annotations` / `olo-plugin-feature-annotations`).

Responsibilities:

- Define **annotations** for:
  - Plugins (`@OloPlugin`, `@OloPluginParam`).
  - Features (`@OloFeature`).
  - UI components (`@OloUiComponent`).
  - Lifecycle hooks (`@ResourceCleanup`).
- Provide an **annotation processor** that:
  - Scans plugin/feature code at compile time.
  - Emits machine-readable JSON descriptors:
    - `META-INF/olo-plugin.json`      (per plugin JAR, serialized `PluginDescriptor`)
    - `META-INF/olo-features.json`
    - `META-INF/olo-ui-components.json`
- Ensure **no runtime dependency** on this module for the worker; only plugin/feature projects add it as `annotationProcessor`.

This module is the single place where configuration and UX metadata are described declaratively.

---

## 2. Current Annotations (Today’s `olo-annotations`)

The existing module already provides a good foundation:

- `@OloFeature` – Feature metadata:
  - Name, phase (e.g. PRE, POST_SUCCESS), applicable node types.
  - Processor generates `META-INF/olo-features.json`.
- `@OloPlugin` – Plugin metadata:
  - Id, contract type, input/output parameters.
  - Processor generates `META-INF/olo-plugin.json` for:
    - Drag-and-drop canvas.
    - Variable mapping and config UX.
- `@OloUiComponent` – UI component metadata:
  - Id, name, category.
  - Processor generates `META-INF/olo-ui-components.json`.
- DTOs:
  - `FeatureInfo`, `PluginInfo`, `UiComponentInfo` – JSON DTOs for loading descriptors.
- Lifecycle:
  - `@ResourceCleanup` – `onExit()` hook so plugins/features can release resources at worker shutdown.

Usage (today):

- Plugin and feature modules depend on `olo-annotations` as:
  - `annotationProcessor project(':olo-annotations')`
- At compile time, JSON descriptors are generated and later loaded by bootstrap.

---

## 3. Design Goals for the Annotation Module

### 3.1 Kernel-friendly

- The kernel (`olo-kernel`) and core runtimes **must not** depend on annotation APIs or processors.
- Annotation module is **compile-time only** for:
  - `olo-plugins`
  - Feature modules
  - Optional UI component modules.

### 3.2 Configuration-driven UX

Annotations should describe:

- **Plugin configuration schema**:
  - Parameters, types, required/optional, defaults, secret flags.
  - Mapping to `ConnectionSchema` / plugin config in the Connection Manager.
- **Feature toggles and placement**:
  - Phases, applicable node types, categories (e.g. logging, metrics, quota).
- **UI hints**:
  - Display names, categories, icons, grouping for palette/canvas.

The processor emits JSON that the **Olo API/UI** and **planner tools** can use to:

- Render connection forms and tool configuration panels.
- Validate pipeline configs before run.
- Drive no-code/low-code experiences.

---

## 4. Relation to Kernel and Runtimes

The annotation module sits **outside** the kernel:

```
olo-kernel          (contracts only)
olo-execution       (runtime)
olo-plugin-runtime  (plugin registry, loader)
olo-feature-runtime (features)
olo-event-system    (events)
   ▲
   │  (loads JSON descriptors)
   │
olo-annotations (compile-time only)
   ▲
   │  (used by plugin/feature code as annotations)
olo-plugins / feature modules
```

- Kernel knows only **interfaces** (`ExecutablePlugin`, `Feature`, `ConnectionRef`, etc.).
- Plugin/feature modules:
  - Implement kernel interfaces.
  - Annotate classes and parameters with `@OloPlugin`, `@OloFeature`, `@OloPluginParam`, etc.
- `olo-plugin-runtime` and `olo-feature-runtime`:
  - Load JSON descriptors from `META-INF` at startup (no reflection scanning).
  - Use descriptors to:
    - Register plugins/features with registries.
    - Provide metadata to API/UI.

**Allowed flow:**

```text
plugin module
   │
   ├─ (annotationProcessor) → olo-annotations
   │
   ↓
META-INF JSON descriptors (per plugin JAR)
   ↓
runtime loads JSON only
```

**Not allowed:**

- Runtime depends directly on `@OloPlugin`, `@OloFeature`, or other annotations.
- Runtime performs classpath/reflection scanning for annotations.

Runtime should read **only** the generated JSON descriptors (e.g. `META-INF/olo-plugin.json`) so:

- Worker startup stays fast (no scanning).
- Plugin JARs are self-describing.
- Kernel and runtimes stay decoupled from annotation details.

---

## 5. Future Extensions (Richer Metadata)

To fully support a **pluggable, UI-driven, pipeline-designer-friendly** experience, the annotation module should describe more than just plugin/feature identities. It should capture:

- Node UI metadata
- Connection configuration
- Secret fields
- Parameter schema
- Feature ordering
- Plugin capabilities
- Planner hints
- Variable outputs
- UI grouping
- Plugin versioning

The sections below outline recommended extensions.

### 5.0 Serialized PluginDescriptor

Every plugin should generate a **Plugin Descriptor** that becomes the single source of truth for identity, capabilities, configuration, runtime behavior, and UI.

This descriptor is the **serialized form of `PluginDescriptor`** from the plugin design spec.

**Compile-time flow (bootstrap-time binding):**

```text
plugin code
   ↓
annotations (@OloPlugin, @OloConfigField, @OloSecret, …)
   ↓
annotation processor
   ↓
META-INF/olo-plugin.json   (serialized PluginDescriptor)
```

**Runtime flow (worker start):**

```text
worker start
   ↓
PluginLoader scans plugin classpath / plugin directory
   ↓
reads META-INF/olo-plugin.json
   ↓
deserialize PluginDescriptor
   ↓
instantiate plugin class directly
   ↓
register in PluginRegistry
```

- No reflection or annotation scanning at runtime.
- Only descriptor parsing and direct class instantiation, based on what the descriptor declares.

**Generated file (per plugin JAR):**

- `META-INF/olo-plugin.json`

**Example:**

```json
{
  "id": "openai.chat",
  "name": "OpenAI Chat",
  "category": "AI/LLM",
  "version": "1.0.0",
  "type": "tool",

  "capabilities": [
    "streaming",
    "retry-safe"
  ],

  "connectionType": "openai",

  "execution": {
    "mode": "ACTIVITY",
    "retrySafe": true,
    "deterministic": false,
    "timeout": "30s"
  },

  "inputs": [
    { "name": "prompt", "type": "string", "required": true },
    { "name": "model", "type": "string", "default": "gpt-4o" }
  ],

  "outputs": [
    { "name": "response", "type": "string" }
  ],

  "ui": {
    "icon": "openai",
    "color": "#10a37f",
    "tags": ["chat", "text-generation"]
  }
}
```

All of this is **derived from annotations** so runtime and UI logic is data-driven, not hard-coded.

### 5.1 Plugin Node Metadata (Palette / Canvas)

Current `@OloPlugin` describes id/contract/params. For a pipeline designer, we also need **node-level metadata**:

```java
@OloPlugin(
    id = "openai.chat",
    name = "OpenAI Chat Completion",
    category = "AI/LLM",
    icon = "openai",
    description = "Generate text using OpenAI models",
    nodeType = NodeType.PLUGIN,
    tags = {"chat", "text-generation"},
    version = "1.2.0",
    deprecated = false
)
```

Additional recommended fields:

- `name`, `description` – human-friendly labels for palette/tooltips.
- `category`, `tags`, `icon` – for grouping and branding in the canvas.
- `version`, `deprecated` – for migration and compatibility warnings.

The processor emits these into `olo-plugin.json` so the UI and planner can build palettes automatically.

### 5.2 Parameter Schema for UI Forms

`@OloPluginParam` should describe a **rich parameter schema** so forms can be generated automatically:

```java
@OloPluginParam(
    name = "model",
    type = ParamType.STRING,
    required = true,
    defaultValue = "gpt-4o",
    description = "Model name",
    uiComponent = "select",
    optionsProvider = "openaiModels"
)
```

Recommended fields:

- `type` – **Olo value types** that match the variable/connection type system:
  - `STRING`
  - `NUMBER`
  - `BOOLEAN`
  - `JSON`
  - `ARRAY`
  - `OBJECT`
- `required` / `defaultValue`
- `enum` / `optionsProvider` – fixed list or dynamic provider id.
- `secret` flag – indicates sensitive values.
- `uiComponent` – input type hint (select, slider, textarea, code, secret, etc.).
- `group` / `order` – for grouping and layout.
- Validation hints (min/max, regex, etc.).

This enables UI rendering like:

- `model` → dropdown
- `temperature` → slider
- `apiKey` → secret field

### 5.3 Connection Configuration Annotations

Plugins should declare their **connection configuration schema** rather than having it live only in docs.

**Generated file:**

- `META-INF/olo-connections.json`

**Example descriptor:**

```json
{
  "connections": [
    {
      "type": "openai",
      "name": "OpenAI API",
      "plugin": "openai.chat",
      "description": "Connection to OpenAI models",

      "config": [
        {
          "name": "baseUrl",
          "type": "string",
          "required": false,
          "default": "https://api.openai.com/v1",
          "description": "API base URL"
        }
      ],

      "secrets": [
        {
          "name": "apiKey",
          "type": "secret",
          "required": true,
          "description": "OpenAI API key"
        }
      ],

      "ui": {
        "category": "AI Providers",
        "icon": "openai"
      }
    }
  ]
}
```

This tells the system:

- Which fields are **config** vs **secrets**.
- How to render the **UI**.
- Which **plugin(s)** require this connection.

**How plugin declares connection metadata (annotations):**

```java
@OloConnection(
    type = "openai",
    name = "OpenAI API",
    category = "AI Providers"
)
public final class OpenAiConnectionConfig {

    @OloConfigField(required = true, description = "Base URL for OpenAI API")
    String baseUrl;

    @OloSecret(description = "OpenAI API Key", required = true)
    String apiKey;
}
```

Generated JSON (e.g. `olo-connections.json` / `olo-config-schemas.json`) describes:

- Connection type/id/name/category.
- Fields and their **Olo types** (`STRING`, `NUMBER`, `BOOLEAN`, `JSON`, `ARRAY`, `OBJECT`), required flags, defaults, descriptions.
- Which fields are secrets.

This schema should map **1:1** to the `ConnectionSchema` structure defined in the Connection Manager spec, for example:

```json
{
  "connectionType": "openai",
  "plugin": "openai.chat",
  "schema": {
    "fields": [
      { "name": "baseUrl", "type": "STRING", "required": false }
    ],
    "secrets": [
      { "name": "apiKey", "required": true }
    ]
  }
}
```

That way, the Connection Manager can consume annotation-generated schemas directly as `ConnectionSchema` without translation layers.

**Runtime flow:**

```text
plugin JAR
    ↓
META-INF/olo-connections.json
    ↓
ConnectionRuntimeManager loads schema
    ↓
UI generates connection form
    ↓
User saves connection
    ↓
Secret stored in secret manager
    ↓
Plugin runtime resolves connection
```

Example resolved connection at runtime:

```json
{
  "type": "openai",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "${secret:tenant/openai/apiKey}"
}
```

Secret manager then resolves `${secret:...}` safely.

**Additional recommended fields:**

To make metadata future-proof:

- `version` – e.g. `"1.0"`.
- `validation` – e.g. `{ "url": true }` for URL fields.
- `testEndpoint` – e.g. `"/models"` for “Test Connection”.
- `capabilities` – e.g. `["chat", "embedding"]` to describe what this connection enables.

Sometimes multiple plugins share a connection type:

```json
{
  "type": "openai",
  "usedBy": [
    "openai.chat",
    "openai.embedding",
    "openai.image"
  ]
}
```

This avoids duplicating connection schemas across plugins.

UI and Connection Manager use this to render connection forms and validate configs.

### 5.4 Secret Annotations

Secrets should be **explicitly annotated** so they can be surfaced in UI and validated by secret managers:

```java
@OloSecret(
    description = "OpenAI API Key",
    required = true
)
String apiKey;
```

Benefits:

- UI can render secret fields appropriately.
- Secret manager can validate references and enforce policies.
- Runtime can treat these values as sensitive (masking, logging rules).

### 5.5 Feature Ordering and Applicability

`@OloFeature` today covers phases; it should also cover **ordering** and applicability:

```java
@OloFeature(
    id = "metrics",
    phase = Phase.PRE,
    order = 100,
    appliesTo = {NodeType.PLUGIN, NodeType.SEQUENCE}
)
```

This allows deterministic ordering when multiple features participate (logging, metrics, quota, audit) and clear scoping by node type.

### 5.6 Plugin Capability Metadata

Some plugins support special capabilities that planners and runtimes should know:

- Streaming
- Batching
- Retry-safe
- Async execution

Example:

```java
@OloPlugin(
    id = "openai.chat",
    capabilities = {
        ChatCapability.class
    }
)
```

The processor can emit a **string capability list** (e.g. `"capabilities": ["chat"]`) that maps back to well-known runtime capability interfaces (e.g. `"chat"` → `ChatCapability.class`, `"embedding"` → `EmbeddingCapability.class`). This keeps:

- Planner capability reasoning (strings).
- Runtime capability interfaces (`ChatCapability`, `EmbeddingCapability`, `ImageCapability`, etc.).
- UI discovery (string capabilities)

all aligned on a single capability model.

These capabilities are emitted as part of `olo-plugin.json` and used for:

- Planner tool selection.
- Runtime routing (e.g. only retry-safe plugins in certain flows).

### 5.7 Planner Hints

For AI planners or auto-builders, plugins can expose **planner hints**:

```java
@OloPlannerHint(
    useCases = {"chat", "text_generation"},
    inputTypes = {"text"},
    outputTypes = {"text"}
)
```

This helps planners choose tools based on:

- Use-case categories.
- Expected input/output types.

### 5.8 Variable Mapping Metadata

To support variable mapping UIs, plugin outputs should be annotated:

```java
@OloPluginOutput(
    name = "response",
    type = "string",
    description = "Generated text"
)
```

Generated metadata describes which outputs are available from a plugin node, so the UI can:

- Offer output variables for mapping.
- Validate mappings against expected types.

### 5.9 UI Layout / Grouping

Configuration fields often belong to logical groups:

```java
@OloConfigField(
    group = "Model Settings",
    order = 1
)
String model;
```

Groups and `order` allow the UI to present well-structured forms (sections, tabs, advanced settings).

### 5.10 Plugin Versioning and Compatibility

Plugins evolve; versioning metadata helps with migration and compatibility:

```java
@OloPlugin(
    id = "openai.chat",
    version = "1.2.0",
    compatibility = ">=1.0"
)
```

This metadata can be used to:

- Warn about deprecated plugins.
- Validate that a pipeline’s required plugin version is available.

### 5.11 Descriptor Files

Beyond the existing descriptor files:

- `META-INF/olo-plugins.json`
- `META-INF/olo-features.json`
- `META-INF/olo-ui-components.json`

the processor can emit additional descriptors:

- `META-INF/olo-connections.json` – connection types and schemas.
- `META-INF/olo-config-schemas.json` – generic config schemas for plugins/tools.
- **Preferred future shape:** a single **per-plugin descriptor** manifest file, `META-INF/olo-plugin.json`, that contains:
  - `plugin` – identity (id, name, version, category, tags).
  - `execution` – `mode` (ExecutionMode), `retrySafe`, `deterministic`, timeouts.
  - `capabilities` – chat, streaming, retry-safe, etc.
  - `connections` – connection types and schemas associated with this plugin.
  - `inputs` / `outputs` – parameter and variable mapping schema.
  - `ui` – icon, color, palette/category metadata.

This “one file per plugin” model simplifies loading:

- Each plugin JAR is self-describing via `META-INF/olo-plugin.json`.
- Runtime logic stays simple: read a single descriptor per plugin, no need to merge multiple files.

These are consumed by:

- `olo-plugin-runtime`, `olo-connection-manager`, `olo-secret-manager`.
- `olo-api` / UI / planner modules.

### 5.12 Recommended Annotation Set

Annotations should also align directly with the **`ExecutionMode`** enum used at runtime. For example:

```java
@OloPlugin(
    id = "openai.chat",
    name = "OpenAI Chat",
    executionMode = ExecutionMode.ACTIVITY
)
```

The annotation processor then emits into the descriptor:

```json
"execution": {
  "mode": "ACTIVITY"
}
```

At runtime, the final execution mode for a node is resolved as:

- Node-level override: `ExecutionTreeNode.executionMode` (if present)  
- Otherwise: plugin default from descriptor: `execution.mode` (which maps to `ExecutionMode`).

This keeps annotation metadata, JSON descriptors, and the `ExecutionMode`-based runtime behavior (see `plugin-design.md`) perfectly aligned.

Putting this together, a mature annotation set could include:

- `@OloPlugin`
- `@OloPluginParam`
- `@OloPluginOutput`
- `@OloFeature`
- `@OloConnection`
- `@OloConfigField`
- `@OloSecret`
- `@OloUiComponent`
- `@ResourceCleanup`
- `@OloPlannerHint`

These cover:

- Plugins and features.
- Connections and secrets.
- Configuration and schema.
- UI and planner metadata.

**Important rule:** the annotation module must remain **compile-time only**. Kernel and runtimes must not depend on annotations or processors; only plugin/feature authors depend on them.

In addition, the **annotation processor should actively validate plugins and connections** and **fail compilation** when metadata is invalid. Examples:

- Missing plugin id or duplicate ids in the same module.
- Duplicate parameter or output names.
- Conflicts between input/output variable names (e.g. same name reused inconsistently).
- Invalid capability references (unknown or unsupported capability constants).

Example compile error:

```text
Plugin openai.chat: duplicate parameter name "model"
```

Doing this at compile time makes plugin development safer and prevents invalid plugins from entering the runtime.

---

## 6. Summary

- **Module name today:** `olo-annotations`.
- **Role:** plugin/feature/configuration **annotation + processor** module.
- **Outputs:** JSON descriptor files that describe:
  - Plugins (id, contract, parameters, config schema).
  - Features (phase, applicable node types).
  - UI components (palette metadata).
- **Consumers:** `olo-plugin-runtime`, `olo-feature-runtime`, `olo-api`/UI, planner modules.
- **Contract with kernel:** kernel remains unaware of annotations; it depends only on the runtime contracts. Annotations enrich the ecosystem and tooling without polluting the kernel surface.

---

## 7. Plugin Discovery (Annotations Perspective)

From the annotation module’s point of view, plugin discovery at runtime is **descriptor-only**:

```text
PluginLoader
   ↓
scan plugin classpath / plugin directory
   ↓
locate META-INF/olo-plugin.json
   ↓
deserialize PluginDescriptor
   ↓
register plugin in PluginRegistry
```

- No reflection or annotation scanning is performed at runtime.
- `META-INF/olo-plugin.json` is the only source of plugin metadata; annotations are used **only at compile time** to generate this descriptor.

