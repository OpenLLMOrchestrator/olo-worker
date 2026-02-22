# 3.x Variable Execution Model

This document defines how variables are declared, initialized, and used during execution tree traversal. The model is enforced by the pipeline configuration (**variableRegistry**, **inputContract**, **outputContract**, **resultMapping**) and by the runtime that runs the tree.

---

## 1. Declared variables only

- **Only variables declared in the pipeline’s `variableRegistry` may be used** during execution.
- Each declaration has: **name**, **type**, and **scope** (`IN`, `INTERNAL`, or `OUT`).
- Any reference to a variable name that is not in the registry is invalid. The runtime must reject or ignore uses of undeclared variables when applying the contract (see strict mode below).

---

## 2. IN variables must match inputContract

- Variables with scope **IN** represent workflow input.
- The set of IN variables must be consistent with the pipeline’s **inputContract**:
  - Every parameter in `inputContract.parameters` (by name) should have a corresponding IN variable in the variable registry (or the registry’s IN set must at least satisfy the contract).
  - IN variable names are the keys used to seed the execution variable map from the workflow input payload.
- **Required** input contract parameters (where `required: true`) must be present in the workflow input; missing required inputs are a contract violation.

---

## 3. INTERNAL variables initialized to null

- Variables with scope **INTERNAL** are not provided by the workflow input and are not exposed in the final result.
- **INTERNAL variables are initialized to null** (or an equivalent “unset” value) at the start of execution.
- They may be written by PLUGIN nodes via **outputMappings** (pluginParameter → variable) and read by later nodes via **inputMappings** (variable → pluginParameter). They are for intermediate results only.

---

## 4. Type validation during inputMappings and outputMappings

- **inputMappings** (on a PLUGIN node) map **variables** → **plugin parameters**. The value read from the variable map must be type-compatible with the plugin’s input parameter type (e.g. STRING).
- **outputMappings** (on a PLUGIN node) map **plugin parameters** → **variables**. The value written from the plugin output must be type-compatible with the variable’s declared type in the registry.
- **Type validation is enforced** when applying these mappings: values are written into the variable map and used in subsequent nodes; type mismatches (where enforced) should be reported or coerced according to implementation policy.

---

## 5. OUT variables must be assigned before completion

- Variables with scope **OUT** contribute to the workflow result via **resultMapping** (variable → outputParameter).
- **Every OUT variable that is referenced in resultMapping must be assigned a value** before execution is considered successful. If an OUT variable is never written by any node (e.g. no outputMapping targets it), the pipeline execution is incomplete or invalid.
- The runtime may validate at the end of traversal that all OUT variables used in resultMapping have been assigned; otherwise the result is undefined or an error is raised.

---

## 6. Unknown variables rejected when strict mode is enabled

- The pipeline’s **inputContract** has a **strict** flag.
- **When `inputContract.strict` is true:**
  - **Unknown variables are rejected:** workflow input must only include names that appear in the input contract (or that correspond to declared IN variables). Extra input names must be rejected or ignored.
  - Input must satisfy the parameter list (required parameters present, no unsanctioned names).
- When strict is false, extra input names may be tolerated (implementation-defined); declared variables are still the only ones used in the tree.

---

## Summary table

| Rule | Description |
|------|-------------|
| Declared only | Only variables in `variableRegistry` may be used. |
| IN ≈ inputContract | IN variables must align with inputContract; required contract parameters must be provided. |
| INTERNAL init | INTERNAL variables are initialized to null at start. |
| Type validation | inputMappings/outputMappings are type-checked (variable/plugin types). |
| OUT assigned | OUT variables referenced in resultMapping must be assigned before completion. |
| Strict mode | When strict is true, unknown input names are rejected. |

---

## Relation to pipeline configuration

- **variableRegistry**: declares every variable (name, type, scope: IN | INTERNAL | OUT).
- **inputContract**: defines allowed/required workflow input (strict, parameters); aligns with IN variables.
- **outputContract** + **resultMapping**: define which OUT variables map to which output parameters; those OUT variables must be assigned during execution.
- **Execution tree**: PLUGIN nodes use inputMappings (variable → pluginParameter) and outputMappings (pluginParameter → variable); only declared variable names may appear there.

See [pipeline-configuration-how-to.md](pipeline-configuration-how-to.md) for the JSON shape of these fields.
