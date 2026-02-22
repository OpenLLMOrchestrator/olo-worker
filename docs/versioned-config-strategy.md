# Versioned Config Strategy

This document defines how **config file version**, **plugin contract version**, and **feature contract version** are used so that config updates are validated before execution and **breaking changes stop the pipeline** (load or run fails with a clear error).

---

## 1. Goals

- **Config file version**: Ensure the loaded JSON config is compatible with the runtime (schema and semantics).
- **Plugin contract version**: Ensure every plugin referenced in the config (by `pluginRef`) has a runtime implementation that matches the contract version declared in scope; mismatches (e.g. config expects 1.0 but plugin is 2.0 with breaking changes) cause validation to fail.
- **Feature contract version**: Same for features; config's scope lists features with an expected contract version; runtime features must satisfy it.
- **Fail before break**: Validation runs **before** execution (and ideally after load). If any check fails, the config is rejected (load fails or execution is not started) and a clear error is returned.

---

## 2. Version Semantics

### 2.1 Config file version

- **Where**: `PipelineConfiguration.version` (root-level string, e.g. `"2.0"`).
- **Meaning**: Schema/semantic version of the pipeline configuration format.
- **Compatibility**: Runtime declares a **supported config version range** (e.g. min/max or "2.x"). Validation rule: config's `version` must fall within the supported range. If the config version is **newer** than the runtime supports (e.g. config 3.0, runtime supports up to 2.x), treat as **incompatible** (breaking). If **older** (e.g. config 1.0, runtime supports 2.x), policy can be "reject" or "accept if backward-compatible"; default: **reject** to avoid running untested combinations.
- **Recommended**: Semantic versioning (major.minor). **Major** change = breaking (different schema or semantics). **Minor** = backward-compatible addition. Runtime supports e.g. `supportedConfigVersionMin = "2.0"`, `supportedConfigVersionMax = "2.x"`; validation ensures `config.version` is in range.

### 2.2 Plugin contract version

- **Where**:
  - **Config (scope)**: Each plugin in `scope.plugins` (PluginDef) can have **contractVersion** (e.g. `"1.0"`). This is the contract version the config was written against (input/output parameters).
  - **Runtime**: Each registered plugin (e.g. via `PluginRegistry`) declares its **contract version** (from `@OloPlugin(contractVersion = "1.0")` or equivalent). The runtime plugin's implementation must honour that contract.
- **Compatibility**: For every **pluginRef** used in the execution tree, resolve the plugin in scope by id; get its **contractVersion** (expected). Get the runtime plugin's contract version. Validation rule: **exact match** or **runtime version ≥ expected** (if runtime uses backward-compatible semantics). Stricter option: **exact match only** so that any plugin upgrade requires a config update and explicit testing.
- **Default**: If scope's PluginDef has no `contractVersion`, treat as "any" (no plugin contract check) or use a default (e.g. `"1.0"`). If runtime plugin has no version, treat as "unknown" and either fail or allow (configurable).

### 2.3 Feature contract version

- **Where**:
  - **Config (scope)**: Each feature in `scope.features` (FeatureDef) can have **contractVersion** (e.g. `"1.0"`).
  - **Runtime**: Each registered feature (e.g. via `FeatureRegistry`) declares its **contract version** (from `@OloFeature(contractVersion = "1.0")` or equivalent).
- **Compatibility**: For every feature id listed in scope or attached to nodes, expected contract version comes from FeatureDef; actual from registry. Same rules as plugin: exact match or runtime ≥ expected; otherwise validation fails.

---

## 3. Validation Flow

All version checks run **during bootstrap** or **during configuration change**, not at execution time.

1. **Bootstrap phase**: After configuration is loaded (into `GlobalConfigurationContext`) and **after** plugins and features are registered, the application runs **ConfigCompatibilityValidator** for every pipeline config (see `OloWorkerApplication`). If any config is incompatible, startup fails with **ConfigIncompatibleException**.

2. **Configuration change**: When new or updated config is stored (e.g. hot reload, admin API), the caller **must** validate first via **VersionedConfigSupport.validateAndPut(queueName, config)**. If validation fails, the config is not stored and **ConfigIncompatibleException** is thrown.

3. **Validator result**: Returns a **ValidationResult** (success or list of error messages). On failure:
   - **Config version incompatible**: e.g. "Config version 3.0 is not in supported range [2.0, 2.x]".
   - **Plugin contract mismatch**: e.g. "Plugin GPT4_EXECUTOR: config expects contract 1.0, runtime has 2.0 (incompatible)".
   - **Feature contract mismatch**: e.g. "Feature debug: config expects contract 1.0, runtime has 2.0 (incompatible)".
   - **Missing plugin/feature**: e.g. "Plugin GPT4_EXECUTOR not found in registry".

4. **Behaviour on failure**: Throw **ConfigIncompatibleException**. At bootstrap: application fails to start. On config change: new config is not stored.

---

## 4. Where Versions Are Stored

| Artefact        | Config / scope                 | Runtime (code)                          |
|-----------------|--------------------------------|-----------------------------------------|
| Config file     | `PipelineConfiguration.version`| Supported range in code or env          |
| Plugin contract | `PluginDef.contractVersion`    | `@OloPlugin.contractVersion`, PluginInfo, registry |
| Feature contract| `FeatureDef.contractVersion`   | `@OloFeature.contractVersion`, FeatureInfo, registry |

---

## 5. JSON Examples

**Pipeline config (root):**
```json
{
  "version": "2.0",
  "executionDefaults": { ... },
  "pipelines": {
    "ai-pipeline": {
      "scope": {
        "plugins": [
          {
            "id": "GPT4_EXECUTOR",
            "contractType": "MODEL_EXECUTOR",
            "contractVersion": "1.0",
            "inputParameters": [ ... ],
            "outputParameters": [ ... ]
          }
        ],
        "features": [
          { "id": "debug", "displayName": "Debug", "contractVersion": "1.0" }
        ]
      },
      "executionTree": { ... }
    }
  }
}
```

**Runtime**: Plugins and features register with their contract version (from annotations or API). Validator compares scope's `contractVersion` with registry's version and fails if incompatible.

---

## 6. Implementation Checklist

- [x] Add **contractVersion** to `PluginDef` and `FeatureDef` (optional string).
- [x] Add **contractVersion** to `@OloPlugin` and `@OloFeature` (default `"1.0"`); emit in PluginInfo / FeatureInfo.
- [x] Extend **PluginRegistry** to store and expose `getContractVersion(pluginId)`; `register(id, contractType, contractVersion, plugin)`.
- [x] Extend **FeatureRegistry** to store and expose `getContractVersion(featureName)`; FeatureEntry has contractVersion from annotation.
- [x] Add **ConfigCompatibilityValidator**: inputs = config, supportedConfigVersionMin/Max, PluginRegistry, FeatureRegistry; output = ValidationResult.
- [x] Add **ConfigIncompatibleException** with ValidationResult detail.
- [x] Run validator **at bootstrap** (in `OloWorkerApplication` after plugins/features are registered) for all pipeline configs; on failure, throw so startup fails. Run validator **on configuration change** via `VersionedConfigSupport.validateAndPut(queueName, config)` before storing new config. No version check at execution time. Optional: add OLO_CONFIG_VERSION_MIN / OLO_CONFIG_VERSION_MAX to enable config file version check.

---

## 7. Related Docs

- [architecture-and-features.md](architecture-and-features.md) — Pipeline config, scope, plugins, features.
- [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) — Config structure and loading.
- [variable-execution-model.md](variable-execution-model.md) — Variable and contract rules.
