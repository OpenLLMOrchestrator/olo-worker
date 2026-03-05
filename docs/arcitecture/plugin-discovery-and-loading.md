# Plugin Discovery and Loading — Specification

This document defines **how plugins enter the Olo system**: JAR structure, manifest, discovery, dependency validation, classloader isolation, and security policy. Together with [plugin-design](plugin-design.md) (what a plugin *is*), this turns the **plugin architecture** into a **plugin ecosystem** — a system where community and internal plugins can be discovered, loaded, and run safely.

---

## Goals

- **Discoverable** — Worker finds plugin JARs from a configured location (e.g. `OLO_PLUGINS_DIR`) without hardcoding.
- **Declarative** — Plugin identity and dependencies come from a **manifest** (or descriptor in JAR), not only from code.
- **Validated** — Dependencies and contract version are checked before a plugin is registered; missing dependencies or incompatible contract fail load.
- **Isolated** — Community plugins run in a **restricted classloader** (and optionally per-plugin classloaders) to avoid conflicts and limit blast radius.
- **Policy-driven** — A **security policy** (filesystem, network, reflection) makes the sandbox explicit and configurable.

---

## Discovery and loading flow (at a glance)

```
worker startup
   ↓
resolve plugin directory (OLO_PLUGINS_DIR or config)
   ↓
scan for plugin JARs (and optional manifest index)
   ↓
for each JAR: read manifest → validate dependencies → create classloader (isolated)
   ↓
instantiate plugin (RuntimePlugin / ExecutablePlugin) via Provider
   ↓
validate contract (descriptor.id, contractVersion) → register in PluginRegistry
   ↓
get(pluginId), list(), findByCapability() available
```

This document defines each step: **JAR structure**, **manifest**, **dependency validation**, **classloader isolation**, **security policy**.

### Plugin discovery diagram

How a plugin JAR becomes a registered plugin:

```
Plugin JAR
   │
   ▼
Manifest (olo-plugin.json)
   │
   ▼
Plugin Loader
   │
   ▼
Classloader Isolation
   │
   ▼
PluginRegistry
```

The loader scans the plugin directory, reads **META-INF/olo-plugin.json** (or MANIFEST.MF), validates dependencies and contract version, creates an isolated classloader (with denied packages), instantiates the **pluginClass** (RuntimePlugin), and registers it in **PluginRegistry**. See §Plugin JAR structure and §Loading sequence.

---

## Plugin JAR structure

A **plugin** is delivered as one or more JARs. The loader must recognize a JAR as an Olo plugin and read its metadata without loading arbitrary code first.

### Required layout (minimum)

- **One JAR per plugin** (or one “main” JAR plus optional dependency JARs in a folder; see §Optional: plugin directory layout).
- **Manifest** — Metadata must be readable from **JAR manifest** (`META-INF/MANIFEST.MF`) or a **manifest file** inside the JAR (e.g. `META-INF/olo-plugin.json`). This allows discovery without instantiating the plugin class.

**Recommended JAR contents:**

```
olo-plugin-openai-1.2.0.jar
├── META-INF/
│   ├── MANIFEST.MF              # JAR manifest (optional Olo entries)
│   └── olo-plugin.json         # Olo plugin manifest (recommended)
├── com/olo/plugin/openai/
│   ├── OpenAIPlugin.class      # implements RuntimePlugin
│   ├── OpenAIClient.class      # implements ModelClient / PluginRuntime
│   ├── OpenAIChatExecutor.class
│   └── ...
└── (plugin dependencies, if bundled; see §Classloader isolation)
```

- **Entry point** — The manifest must identify the **plugin class** (e.g. `Olo-Plugin-Class: com.olo.plugin.openai.OpenAIPlugin`) so the loader can instantiate it (or the equivalent in `olo-plugin.json`).

### Manifest in MANIFEST.MF (optional)

Standard JAR manifest can carry Olo-specific attributes:

```
Manifest-Version: 1.0
Olo-Plugin-Id: openai
Olo-Plugin-Class: com.olo.plugin.openai.OpenAIPlugin
Olo-Plugin-Version: 1.2.0
Olo-Contract-Version: 1.0
```

If the loader uses only `MANIFEST.MF`, it can discover plugin id, class, version, and contract version without parsing JSON. Downside: less room for capabilities, dependencies, vendor; those can live in code (PluginDescriptor) once the class is loaded.

### Manifest in olo-plugin.json (recommended)

A dedicated manifest file allows rich metadata **before** any plugin class is loaded:

```json
{
  "id": "openai",
  "displayName": "OpenAI",
  "vendor": "openai",
  "version": "1.2.0",
  "type": "MODEL",
  "contractVersion": "1.0",
  "capabilities": ["chat", "embeddings", "images"],
  "dependencies": [],
  "pluginClass": "com.olo.plugin.openai.OpenAIPlugin"
}
```

- **pluginClass** — Fully qualified class name; **must implement RuntimePlugin** (or PluginBootstrap). The loader instantiates this class and expects that contract; see §Plugin manifest (schema) validation rules.
- Other fields align with **PluginDescriptor** in [plugin-design](plugin-design.md). The loader can validate **dependencies** and **contractVersion** using only this file; then load the class and register.

**Example: complete manifest file** — Plugin authors can use this as a template. Place it at **META-INF/olo-plugin.json** inside the JAR:

```json
{
  "id": "openai",
  "displayName": "OpenAI",
  "vendor": "openai",
  "version": "1.2.0",
  "type": "MODEL",
  "contractVersion": "1.0",
  "capabilities": ["chat", "embeddings", "images"],
  "dependencies": [],
  "pluginClass": "com.olo.plugin.openai.OpenAIPlugin"
}
```

Required: **id**, **pluginClass**, **version**, **contractVersion**. Recommended: displayName, vendor, type, capabilities, dependencies. Version should follow semver (e.g. `1.2.0`) for future compatibility checks.

### Optional: plugin directory layout

For a single “plugin” that ships multiple JARs (e.g. plugin + native libs or optional deps):

```
OLO_PLUGINS_DIR/
├── openai/
│   ├── olo-plugin-openai-1.2.0.jar    # main plugin JAR (has manifest)
│   └── lib/                           # optional: extra JARs for this plugin
│       └── some-helper.jar
├── ollama/
│   └── olo-plugin-ollama-0.1.0.jar
└── olo-plugin-standalone.jar          # flat JARs also supported
```

Loader behavior: **scan** for JARs (flat and/or per-directory); each JAR that contains an Olo manifest is treated as one plugin. Directory name need not match plugin id; **id** comes from the manifest.

---

## Plugin manifest (schema)

The **plugin manifest** is the source of truth for discovery and validation before any plugin code runs.

### Required fields

| Field | Type | Meaning |
|-------|------|---------|
| **id** | string | Plugin id; must be unique in the registry. Matches `connection.plugin` and `PluginDescriptor.id()`. |
| **pluginClass** | string | FQCN of the class that the loader instantiates. **Must implement RuntimePlugin** (or a defined bootstrap interface such as **PluginBootstrap** if the loader supports it). No ambiguity: the loader expects this contract; if the class does not implement it, **reject load**. |
| **version** | string | Plugin implementation version. **Semantic version (semver)** recommended (e.g. `1.2.0`); enables compatibility ranges later (e.g. `>=1.2`, `<2.0`) for dependency and marketplace rules. For display and marketplace. |
| **contractVersion** | string | Contract version (e.g. `1.0`). Used to check compatibility with the Olo runtime; see [plugin-design](plugin-design.md) §Version semantics. |

### Recommended fields

| Field | Type | Meaning |
|-------|------|---------|
| **displayName** | string | Human-readable name (e.g. "OpenAI"). |
| **vendor** | string | Vendor or organization. |
| **type** | string | PluginType: MODEL, VECTOR_DB, TOOL, STORAGE, etc. |
| **capabilities** | array of string | e.g. `["chat", "embeddings", "images"]`; used for **findByCapability** and pipeline validation. |
| **dependencies** | array of string | Plugin ids this plugin depends on (e.g. `["vector-plugin"]`). Validated at load time. |

### Validation rules

- **id** — Non-empty, unique among plugins being loaded; recommended pattern `[a-z][a-z0-9-]*`.
- **pluginClass** — Class must exist in the plugin JAR and **must implement RuntimePlugin** (or **PluginBootstrap**, if defined). If the class does not implement the required interface, **reject load** and log.
- **contractVersion** — Worker may enforce a minimum supported contract; if plugin’s contractVersion is below that, **reject load** and log.
- **dependencies** — Every listed plugin id must be present in the registry (or in the same load batch). If any dependency is missing, **reject load** and log the missing id(s). See §Dependency validation.

---

## Dependency validation

Plugins can declare **dependencies** on other plugins (e.g. a RAG plugin depends on a vector plugin and an embedding plugin). Validation ensures the system never registers a plugin whose dependencies are missing.

### When to validate

- **At load time** — After discovering all JARs and reading manifests, build a dependency graph (id → set of dependency ids). Before instantiating any plugin:
  1. Resolve load order (topological sort) so dependencies are registered before dependents.
  2. For each plugin, check that every **dependencies** entry is either already registered (internal or previously loaded community plugin) or present in the current load set.
  3. If any dependency is missing, **fail the load** of the dependent plugin and log (e.g. “Plugin rag-plugin depends on vector-plugin, which is not registered”).

### Cycle detection

- If plugin A depends on B and B depends on A (directly or transitively), **fail load** and report a circular dependency. Topological sort will detect this.

### Order of registration

- Register plugins in **dependency order**: dependencies first, then dependents. So when a plugin class is instantiated and possibly looks up another plugin (e.g. in init), the dependency is already in the registry.

### Manifest vs descriptor: which is authoritative

- **Manifest** — **Authoritative during loading.** The loader reads the manifest to validate dependencies, contract version, and plugin class before any plugin code runs. All load-time decisions use the manifest.
- **Descriptor** — **Authoritative during runtime.** After the plugin is instantiated, `plugin.descriptor()` (PluginDescriptor) is the source of truth for id, capabilities, dependencies in the registry, UI, and pipeline resolution.
- **Rule:** Manifest and descriptor should reflect the same identity and dependencies. **If manifest and descriptor differ** (e.g. different id, contractVersion, or dependencies), **the loader rejects the plugin** and does not register it. This avoids ambiguity about which to trust.

### Relation to plugin-design

- **PluginDescriptor.dependencies()** (in [plugin-design](plugin-design.md)) should reflect the same set as **manifest dependencies**. The manifest is authoritative for **loading**; the descriptor is authoritative for **runtime** discovery and UI (“plugins that depend on X”).

---

## Classloader isolation

Community plugins must not see the worker’s full classpath (to avoid version clashes and accidental use of internal APIs). They should see only:

- **Olo plugin API** (contracts: RuntimePlugin, PluginDescriptor, PluginRuntime, ExecutablePlugin, etc.).
- **Libraries the worker explicitly exposes** (e.g. a minimal SLF4J, or shared HTTP client API).
- **Their own JAR** (and optionally their lib folder).

### Parent-last (child-first) classloader

- **Plugin classloader** has the **worker’s classloader** as parent (or a filtered “API” classloader as parent). For plugin classes and resources, use **plugin-first**: load from the plugin JAR (and its libs) before delegating to parent. So the plugin gets its own versions of libraries that are not part of the exposed API.
- **Worker (and API) classes** are loaded from the parent. This prevents a plugin from supplying a different version of `RuntimePlugin` or worker internals.

### Denied packages (must not load from plugin)

- **Deny list** — Packages that the plugin must not load from its own JAR; they must come from the parent. Typical list:
  - `com.olo.worker.` (worker internals)
  - `com.olo.plugin.` (other plugins; unless we explicitly allow a shared “api” package)
  - `org.slf4j.` (if worker exposes logging via a facade only)
  - Any package that would allow reflection into worker or other plugins
- If the plugin JAR contains classes in denied packages, the classloader **loads them from parent** (or refuses to load if parent doesn’t define them). This prevents plugins from overriding worker or API types.

### One classloader per plugin (optional but recommended)

- **One ClassLoader per plugin JAR** (or per plugin id). Benefits:
  - **Isolation** — Plugin A’s dependencies do not conflict with Plugin B’s (e.g. different Guava versions).
  - **Clear unloading** — If hot reload is added later, dropping one plugin does not affect others.
- Alternative: a **shared “community” classloader** for all community plugins (simpler but dependency conflicts possible). Prefer one-per-plugin for a real ecosystem.

### Relation to plugin-design

- [plugin-design](plugin-design.md) §Plugin sandbox mentions “restricted classloader (denied packages)” and “one classloader per plugin” as future direction. This document makes that concrete: **parent-last**, **denied packages**, and **one classloader per plugin** as the recommended loading model.

---

## Security policy

Beyond classloader isolation, a **security policy** defines what a plugin is allowed to do at runtime: filesystem, network, reflection. This is especially important for **community plugins** from untrusted sources.

### Policy dimensions

| Dimension | Purpose | Example rules |
|-----------|---------|----------------|
| **Filesystem** | Limit where plugins can read/write. | Allow only a temp directory; or a configured “plugin workspace” path; deny access to worker config and other plugins. |
| **Network** | Control outbound connections. | Allowlist of hosts (e.g. `api.openai.com`, `*.ollama.local`); or “no network” for untrusted plugins. |
| **Reflection** | Limit what can be reflected. | Allow only public API types (e.g. from `olo-worker-plugin`); deny access to worker internals and other plugin classes. |
| **System** | Limit system calls and env. | Deny `System.exit`, restrict `System.getenv` to a allowlist, or block entirely. |

### Default policy (recommended)

- **Internal plugins** — No sandbox; full worker classpath and permissions (they are part of the product).
- **Community plugins** — **Deny by default**; allow only what is explicitly permitted:
  - **Classloader**: as above (API + own JAR, denied packages).
  - **Filesystem**: read-only to a configured dir (e.g. plugin resources) or none; write only to a temp/workspace dir if needed.
  - **Network**: allowlist of hosts derived from connection config or a static list; no raw socket to arbitrary hosts.
  - **Reflection**: only public API; no setAccessible on worker or other plugin types.

### Configuration

- Policy can be **global** (all community plugins) or **per-plugin** (e.g. by plugin id or by “trust level”). Example config (conceptual):

```yaml
plugin:
  security:
    default:
      filesystem: "read:none, write:temp"
      network: "allowlist: [api.openai.com, api.anthropic.com]"
      reflection: "apiOnly"
    overrides:
      "olo-plugin-openai": { network: "allowlist: [*.openai.com]" }
```

- Operators can tighten or loosen policy per environment (e.g. stricter in production).

### Enforcement

- **Classloader** enforces which classes can be loaded.
- **Filesystem / network / reflection** can be enforced by:
  - **Java SecurityManager** (deprecated but possible); or
  - **Wrappers** — Plugin code runs behind a layer that checks file paths and network targets against the policy; or
  - **Process isolation** (future: run plugin in a separate process with strict limits).
- Document the chosen enforcement mechanism and its limitations so operators know what “security policy” guarantees.

### Relation to plugin-design

- [plugin-design](plugin-design.md) §Plugin sandbox (community plugins) calls out filesystem, network, and reflection as directions. This document turns that into a **policy** with dimensions, default (deny by default for community), configuration, and enforcement options.

---

## Loading sequence (detailed)

1. **Resolve plugin directory** — From env `OLO_PLUGINS_DIR` or config; if missing or empty, skip community plugin loading.
2. **Scan** — List JARs (flat and/or per-directory). Filter by naming convention optional (e.g. `olo-plugin-*.jar`) or accept any JAR that contains an Olo manifest.
3. **Read manifest** — For each JAR, read `META-INF/olo-plugin.json` (or MANIFEST.MF). If no Olo manifest, skip JAR (or log and skip).
4. **Validate manifest** — Check required fields, id uniqueness, contractVersion compatibility, and **dependencies** (dependency ids must exist or be in the load set).
5. **Resolve order** — Topological sort by dependencies; fail on cycle.
6. **Create classloader** — For each plugin (in order), create a **PluginClassLoader** (parent = worker API loader, parent-last for plugin JAR and libs), with **denied packages** applied.
7. **Instantiate** — Load `pluginClass` from plugin classloader; instantiate (e.g. via Provider or reflection); obtain **RuntimePlugin** (or bootstrap interface).
8. **Cross-check descriptor** — Compare manifest with `plugin.descriptor()` (id, contractVersion, dependencies). **If manifest and descriptor differ, reject the plugin** (do not register); see §Manifest vs descriptor.
9. **Register** — Register plugin (or PluginProvider) in **PluginRegistry** under `descriptor.id()`.
10. **Post-load** — Emit **PluginLoadedEvent** (or log); **list()** and **findByCapability()** now include the new plugin.

If any step fails (manifest invalid, dependency missing, class not found, descriptor mismatch), behavior depends on **plugin load mode** (see §Plugin load modes): **permissive** — do not register that plugin, log clearly, continue with others; **strict** — fail startup.

---

## Plugin load modes

Some systems allow configurable behavior when a plugin fails validation or load. Making this explicit helps operators.

| Mode | Behavior |
|------|----------|
| **Strict** | **Fail startup** if any plugin is invalid (manifest invalid, dependency missing, class not found, descriptor mismatch). No plugins are registered until all pass. |
| **Permissive** | **Skip** the bad plugin; log the error; continue loading others. Invalid plugins are not registered; valid ones are. |

**Configuration (conceptual):**

- `plugin.loading.mode = strict | permissive` (e.g. in config or env `OLO_PLUGIN_LOADING_MODE`).
- **Default:** Document the default (e.g. permissive for backward compatibility, or strict for production). The current document’s “do not register that plugin; log clearly and continue with others” implies **permissive**; making it configurable allows operators to choose strict when they want a clean fail-fast startup.

---

## Plugin lifecycle (phases)

A single diagram helps maintainers see the full path from discovery to shutdown:

```
scan          — discover JARs in plugin directory
   ↓
validate      — manifest, dependencies, contractVersion, (optional) signature
   ↓
load          — create classloader, instantiate pluginClass (RuntimePlugin)
   ↓
register      — add to PluginRegistry (get, list, findByCapability)
   ↓
init          — optional PluginLifecycle.init() if supported
   ↓
runtime usage — createRuntime(), execute(), capability resolution
   ↓
shutdown      — ResourceCleanup.onExit() / PluginLifecycle.stop()
```

---

## Plugin signature verification (future direction)

Right now the worker **trusts plugin JARs** from the configured directory. Large plugin ecosystems often add **plugin signing** so that only verified artifacts are loaded.

**Example flow:**

- **Plugin JAR** — The signed artifact (e.g. `olo-plugin-openai-1.2.0.jar`).
- **Signature file** — e.g. `META-INF/olo-plugin.sig` (or a sidecar `.sig` file) containing a signature over the JAR (or over the manifest).
- **Public key verification** — Worker (or marketplace) holds public keys for trusted vendors; before load, verify the signature. If verification fails, **reject load** (or treat as untrusted and apply stricter sandbox).

**This allows:**

- **Trusted vendors** — Only load plugins signed by keys the operator has approved.
- **Verified marketplace plugins** — Marketplace can require signing; workers only accept signed JARs.

Optional but valuable for **enterprise deployments** where supply-chain trust is required. Document as a future direction; implementation can follow later (e.g. standard JAR signing or custom `olo-plugin.sig` format).

---

## Summary

| Aspect | Design |
|--------|--------|
| **JAR structure** | One JAR per plugin (or main JAR + lib folder). Manifest in `META-INF/olo-plugin.json` (recommended) or MANIFEST.MF. Required: id, pluginClass, version, contractVersion. |
| **Manifest** | Declarative metadata (id, displayName, vendor, type, capabilities, dependencies). Validated before class load. Aligns with PluginDescriptor. |
| **Dependency validation** | At load time: dependency graph, topological order, missing dependency → fail load. Cycle → fail load. Register dependencies before dependents. |
| **Classloader** | Parent-last (plugin-first) for plugin JAR. Denied packages (worker internals, other plugins). One classloader per plugin recommended. |
| **Security policy** | Filesystem, network, reflection (and optionally system). Deny by default for community plugins; allowlist/config. Document enforcement (classloader, wrappers, or future process isolation). |
| **Load mode** | Configurable: **strict** (fail startup if any plugin invalid) or **permissive** (skip bad plugin, continue). Default permissive implied by current behavior. |
| **Future** | Plugin signature verification (META-INF/olo-plugin.sig, trusted vendors, enterprise). |

This gives Olo a **plugin ecosystem**: plugins are **discovered** from a directory, **described** by a manifest, **validated** for dependencies and contract, **isolated** by classloader, and **constrained** by policy. Together with [plugin-design](plugin-design.md), the system supports both the *what* (contract, descriptor, registry) and the *how* (discovery, loading, isolation, security).

---

## Related architecture docs

| Document | Description |
|----------|-------------|
| [plugin-design](plugin-design.md) | Plugin contract, PluginDescriptor, PluginRuntime, PluginRegistry, execution vs runtime, capabilities, lifecycle. |
| [plugin-discovery-and-loading](plugin-discovery-and-loading.md) | **This document.** JAR structure, manifest, dependency validation, classloader isolation, security policy. |

For the full list of architecture docs, see [plugin-design](plugin-design.md) §Related architecture docs.
