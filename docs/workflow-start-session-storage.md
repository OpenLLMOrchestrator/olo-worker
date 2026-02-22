# Workflow start: deserialize and push to session (USERINPUT)

At workflow start, the workflow input payload must be deserialized and pushed to the session key so it is available for the run.

## Environment

| Variable | Description | Default |
|----------|-------------|---------|
| `OLO_SESSION_DATA` | Prefix for session keys. User input is stored at `<OLO_SESSION_DATA><transactionId>:USERINPUT`. | `olo:kernel:sessions:` |

Example key: `olo:kernel:sessions:8huqpd42mizzgjOhJEH9C:USERINPUT`

## Flow

1. Workflow receives the raw input (JSON string).
2. Deserialize: `WorkflowInput input = WorkflowInput.fromJson(rawInput)`.
3. Push to session: store `input.toJson()` at key `OLO_SESSION_DATA + transactionId + ":USERINPUT"` using your cache (e.g. Redis).

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
// input is deserialized and stored at olo:kernel:sessions:<transactionId>:USERINPUT

// Option B: you already have a deserialized WorkflowInput
WorkflowInput input = WorkflowInput.fromJson(rawInput);
SessionUserInputStorage.store(input, config.getSessionDataPrefix(), sessionCache);
```

Key used: `config.getSessionUserInputKey(input.getRouting().getTransactionId())`  
→ e.g. `olo:kernel:sessions:8huqpd42mizzgjOhJEH9C:USERINPUT`

Value stored: `input.toJson()` (the full workflow input JSON).
