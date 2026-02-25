# Feature ordering diagram

This document gives a single reference for **feature execution order**: phase flow per node and resolved feature order within each phase. See [architecture-and-features.md § 3.2.1](architecture-and-features.md#321-feature-execution-order-defined-contract) for the full contract and order determinism.

---

## 1. Phase execution flow (per node)

For each execution tree node, the executor runs phases in this order:

```
    ┌─────────────────────────────────────┐
    │  PRE                                │  ← All features in resolved pre list (in order)
    └─────────────────────────────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  NODE EXECUTION                     │  ← Plugin invoke, sequence no-op, etc.
    └─────────────────────────────────────┘
                      ↓
         ┌────────────┴────────────┐
         ↓                         ↓
    ┌─────────────┐          ┌─────────────┐
    │ POST_SUCCESS│          │ POST_ERROR  │  ← On normal completion   ← On exception
    └─────────────┘          └─────────────┘
         └────────────┬────────────┘
                      ↓
    ┌─────────────────────────────────────┐
    │  FINALLY                             │  ← Always runs (after postSuccess or postError)
    └─────────────────────────────────────┘
```

**Summary:** PRE → NODE → (POST_SUCCESS or POST_ERROR) → FINALLY.

---

## 2. Resolved feature order (within each phase)

The order of feature names in each list (pre, postSuccess, postError, finally) is determined by **merge order**. First occurrence wins; no duplicates. **featureNotRequired** excludes at any step.

```
    Merge order (first → last = execution order):

    1. Node explicit    preExecution, postSuccessExecution, postErrorExecution, finallyExecution
    2. Legacy           postExecution (→ all three post lists)
    3. Node shorthand   features        ← order preserved; same feature here and in scope → position from here
    4. Scope + queue    pipeline scope features, queue-based (e.g. -debug → "debug")   ← order preserved
    5. Required         featureRequired

    Excluded at any step:  featureNotRequired
```

**Order determinism:**

- **scope.features** order is preserved when adding.
- **node.features** order is preserved when adding.
- If the same feature appears in both **node.features** and **scope.features**, **node.features wins** for position (processed before scope).

---

## 3. Related docs

- [architecture-and-features.md](architecture-and-features.md) — Full feature execution order and order determinism.
- [creating-plugins-and-features.md](creating-plugins-and-features.md) — How to create features and attach them to nodes.
