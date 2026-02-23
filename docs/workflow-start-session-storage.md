# Workflow start: deserialize and push to session (USERINPUT)

At workflow start, the workflow input payload must be deserialized and pushed to the session key so it is available for the run.

## Environment

| Variable | Description | Default |
|----------|-------------|---------|
| `OLO_SESSION_DATA` | Prefix for session keys; the literal `<tenant>` is replaced with the tenant id at runtime. User input is stored at `getSessionDataPrefix(tenantId)` + `<transactionId>` + `:USERINPUT`. | `<tenant>:olo:kernel:sessions:` |

Example keys: `default:olo:kernel:sessions:8huqpd42mizzgjOhJEH9C:USERINPUT` or with a UUID tenant id `2a2a91fb-f5b4-4cf0-b917-524d242b2e3d:olo:kernel:sessions:wf-001:USERINPUT`. Keys are always **tenant-first** (`<tenantId>:olo:kernel:sessions:...`), including when using legacy env `OLO_SESSION_DATA=olo:kernel:sessions:`.

## Flow

1. Workflow receives the raw input (JSON string).
2. Deserialize: `WorkflowInput input = WorkflowInput.fromJson(rawInput)`.
3. Push to session: store `input.toJson()` at key `config.getSessionDataPrefix(tenantId) + transactionId + ":USERINPUT"` (tenant from `input.getContext().getTenantId()`), using your cache (e.g. Redis). The result is always a tenant-first key like `<tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT`.

## Code (worker / workflow start)

Use config for the prefix and the input module’s session helper:

```java
import com.olo.config.OloConfig;
import com.olo.input.model.WorkflowInput;
import com.olo.input.producer.CacheWriter;
import com.olo.input.producer.SessionUserInputStorage;

// At workflow start (e.g. in your workflow or in a starter that runs before the worker picks up the task):
OloConfig config = OloConfig.fromEnvironment();
CacheWriter sessionCache = (key, value) -> redis.set(key, value);  // your Redis/cache

// Option A: you already have the raw JSON string
String rawInput = ...;  // e.g. workflow.getInput()
WorkflowInput input = SessionUserInputStorage.deserializeAndStore(rawInput, config.getSessionDataPrefix(), sessionCache);
// input is deserialized and stored at <tenantId>:olo:kernel:sessions:<transactionId>:USERINPUT (use getSessionDataPrefix(tenantId) for tenant from input.getContext())

// Option B: you already have a deserialized WorkflowInput
WorkflowInput input = WorkflowInput.fromJson(rawInput);
SessionUserInputStorage.store(input, config.getSessionDataPrefix(), sessionCache);
```

Key used (tenant-scoped): `config.getSessionDataPrefix(input.getContext().getTenantId()) + transactionId + ":USERINPUT"`  
→ e.g. `default:olo:kernel:sessions:8huqpd42mizzgjOhJEH9C:USERINPUT`

Value stored: `input.toJson()` (the full workflow input JSON).
