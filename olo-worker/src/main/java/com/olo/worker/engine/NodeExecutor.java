package com.olo.worker.engine;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;
import com.olo.features.ResolvedPrePost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Single responsibility: execute one node (pre then execute then post).
 * Dispatches by node type: SEQUENCE, IF, SWITCH, ITERATOR, FORK, JOIN, PLUGIN;
 * Phase 2: TRY_CATCH, RETRY, SUB_PIPELINE, EVENT_WAIT; Phase 3: LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION.
 * See docs/node-type-catalog.md for type semantics and params.
 */
public final class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final PluginInvoker pluginInvoker;
    private final PipelineConfiguration config;
    private final ExecutionType executionType;
    private final ExecutorService executor;

    public NodeExecutor(PluginInvoker pluginInvoker, PipelineConfiguration config,
                        ExecutionType executionType, ExecutorService executor) {
        this.pluginInvoker = pluginInvoker;
        this.config = config;
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.executor = executor;
    }

    public void executeNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName) {
        if (node == null) return;
        boolean runAsync = executionType == ExecutionType.ASYNC
                && executor != null
                && node.getType() != NodeType.JOIN;
        if (runAsync) {
            Future<?> future = executor.submit(() ->
                    executeNodeSync(node, pipeline, variableEngine, queueName));
            try {
                future.get();
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
        } else {
            executeNodeSync(node, pipeline, variableEngine, queueName);
        }
    }

    private void executeNodeSync(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName) {
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        NodeExecutionContext context = new NodeExecutionContext(
                node.getId(), node.getType().getTypeName(), node.getNodeType());
        runPre(resolved, context, registry);
        Object nodeResult = null;
        try {
            nodeResult = dispatchExecute(node, pipeline, variableEngine, queueName);
            runPostSuccess(resolved, context, nodeResult, registry);
        } catch (Throwable t) {
            runPostError(resolved, context, null, registry);
            throw t;
        } finally {
            runFinally(resolved, context, nodeResult, registry);
        }
    }

    private void runPre(ResolvedPrePost resolved, NodeExecutionContext context, FeatureRegistry registry) {
        for (String name : resolved.getPreExecution()) {
            FeatureRegistry.FeatureEntry e = registry.get(name);
            if (e == null) continue;
            Object inst = e.getInstance();
            if (inst instanceof PreNodeCall) {
                ((PreNodeCall) inst).before(context);
            }
        }
    }

    private void runPostSuccess(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                               FeatureRegistry registry) {
        for (String name : resolved.getPostSuccessExecution()) {
            runPostFeature(name, context, nodeResult, registry);
        }
    }

    private void runPostError(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                             FeatureRegistry registry) {
        for (String name : resolved.getPostErrorExecution()) {
            runPostFeature(name, context, nodeResult, registry);
        }
    }

    private void runFinally(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                           FeatureRegistry registry) {
        for (String name : resolved.getFinallyExecution()) {
            runPostFeature(name, context, nodeResult, registry);
        }
    }

    private void runPostFeature(String name, NodeExecutionContext context, Object nodeResult,
                               FeatureRegistry registry) {
        FeatureRegistry.FeatureEntry e = registry.get(name);
        if (e == null) return;
        Object inst = e.getInstance();
        if (inst instanceof PostNodeCall) {
            try {
                ((PostNodeCall) inst).after(context, nodeResult);
            } catch (Throwable t) {
                log.warn("Post feature {} failed", name, t);
            }
        }
    }

    private Object dispatchExecute(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName) {
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) {
            log.debug("Node {} has null or unknown type; skipping", node.getId());
            return null;
        }
        return switch (type) {
            case SEQUENCE -> executeSequence(node, pipeline, variableEngine, queueName);
            case IF -> executeIf(node, pipeline, variableEngine, queueName);
            case SWITCH -> executeSwitch(node, pipeline, variableEngine, queueName);
            case ITERATOR -> executeIterator(node, pipeline, variableEngine, queueName);
            case FORK -> executeFork(node, pipeline, variableEngine, queueName);
            case JOIN -> executeJoin(node, pipeline, variableEngine, queueName);
            case PLUGIN -> pluginInvoker.invoke(node, variableEngine);
            case CASE -> executeCase(node, pipeline, variableEngine, queueName);
            case TRY_CATCH -> executeTryCatch(node, pipeline, variableEngine, queueName);
            case RETRY -> executeRetry(node, pipeline, variableEngine, queueName);
            case SUB_PIPELINE -> executeSubPipeline(node, pipeline, variableEngine, queueName);
            case EVENT_WAIT -> executeEventWait(node, variableEngine);
            case LLM_DECISION -> executeLlmDecision(node, variableEngine);
            case TOOL_ROUTER -> executeToolRouter(node, pipeline, variableEngine, queueName);
            case EVALUATION -> executeEvaluation(node, variableEngine);
            case REFLECTION -> executeReflection(node, variableEngine);
            case UNKNOWN -> null;
        };
    }

    private Object executeSequence(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName) {
        for (ExecutionTreeNode child : node.getChildren()) {
            executeNode(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeIf(ExecutionTreeNode node, PipelineDefinition pipeline,
                             VariableEngine variableEngine, String queueName) {
        String conditionVar = paramString(node, "conditionVariable");
        boolean condition = true;
        if (conditionVar != null && !conditionVar.isBlank()) {
            Object val = variableEngine.get(conditionVar);
            condition = isTruthy(val);
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (condition && !children.isEmpty()) {
            executeNode(children.get(0), pipeline, variableEngine, queueName);
        } else if (!condition && children.size() > 1) {
            executeNode(children.get(1), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeSwitch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName) {
        String switchVar = paramString(node, "switchVariable");
        if (switchVar == null || switchVar.isBlank()) {
            log.warn("SWITCH node {} missing switchVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(switchVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            if (child.getType() != NodeType.CASE) continue;
            Object caseVal = child.getParams().get("caseValue");
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                executeNode(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        log.debug("SWITCH node {} no matching CASE for value={}", node.getId(), value);
        return null;
    }

    private Object executeCase(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName) {
        for (ExecutionTreeNode child : node.getChildren()) {
            executeNode(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeIterator(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName) {
        String collectionVar = paramString(node, "collectionVariable");
        String itemVar = paramString(node, "itemVariable");
        String indexVar = paramString(node, "indexVariable");
        if (collectionVar == null || itemVar == null) {
            log.warn("ITERATOR node {} missing collectionVariable or itemVariable in params", node.getId());
            return null;
        }
        Object coll = variableEngine.get(collectionVar);
        if (!(coll instanceof Collection)) {
            log.warn("ITERATOR node {} collectionVariable {} is not a Collection", node.getId(), collectionVar);
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        ExecutionTreeNode body = children.get(0);
        int index = 0;
        for (Object item : (Collection<?>) coll) {
            variableEngine.put(itemVar, item);
            if (indexVar != null && !indexVar.isBlank()) {
                variableEngine.put(indexVar, index);
            }
            executeNode(body, pipeline, variableEngine, queueName);
            index++;
        }
        return null;
    }

    private Object executeFork(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName) {
        for (ExecutionTreeNode child : node.getChildren()) {
            executeNode(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeJoin(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName) {
        String strategy = paramString(node, "mergeStrategy");
        if (strategy == null || strategy.isBlank()) {
            log.warn("JOIN node {} missing mergeStrategy in params; required (ALL, ANY, FIRST_WINS, LAST_WINS, MAJORITY, REDUCE, PLUGIN)", node.getId());
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        switch (strategy.toUpperCase()) {
            case "ANY":
            case "FIRST_WINS":
                if (!children.isEmpty()) {
                    executeNode(children.get(0), pipeline, variableEngine, queueName);
                }
                break;
            case "LAST_WINS":
                for (ExecutionTreeNode child : children) {
                    executeNode(child, pipeline, variableEngine, queueName);
                }
                break;
            case "PLUGIN":
                for (ExecutionTreeNode child : children) {
                    executeNode(child, pipeline, variableEngine, queueName);
                }
                if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                    return pluginInvoker.invoke(node, variableEngine);
                }
                break;
            case "ALL":
            case "MAJORITY":
            case "REDUCE":
            default:
                for (ExecutionTreeNode child : children) {
                    executeNode(child, pipeline, variableEngine, queueName);
                }
                break;
        }
        return null;
    }

    private Object executeTryCatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                    VariableEngine variableEngine, String queueName) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        try {
            executeNode(children.get(0), pipeline, variableEngine, queueName);
        } catch (Throwable t) {
            String errorVar = paramString(node, "errorVariable");
            if (errorVar != null && !errorVar.isBlank()) {
                variableEngine.put(errorVar, t.getMessage() != null ? t.getMessage() : t.toString());
            }
            if (children.size() > 1) {
                executeNode(children.get(1), pipeline, variableEngine, queueName);
            } else {
                throw t;
            }
        }
        return null;
    }

    private Object executeRetry(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            log.warn("RETRY node {} has no child", node.getId());
            return null;
        }
        int maxAttempts = paramInt(node, "maxAttempts", 3);
        long initialMs = paramLong(node, "initialIntervalMs", 0L);
        double backoffCoefficient = paramDouble(node, "backoffCoefficient", 2.0);
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                executeNode(children.get(0), pipeline, variableEngine, queueName);
                return null;
            } catch (Throwable t) {
                last = t;
                if (attempt == maxAttempts) break;
                if (!isRetryable(node, t)) throw t;
                long sleepMs = (long) (initialMs * Math.pow(backoffCoefficient, attempt - 1));
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", e);
                    }
                }
            }
        }
        throw last instanceof RuntimeException ? (RuntimeException) last : new RuntimeException(last);
    }

    private Object executeSubPipeline(ExecutionTreeNode node, PipelineDefinition pipeline,
                                       VariableEngine variableEngine, String queueName) {
        if (config == null || config.getPipelines() == null) {
            log.warn("SUB_PIPELINE node {} has no PipelineConfiguration; skipping", node.getId());
            return null;
        }
        String pipelineRef = paramString(node, "pipelineRef");
        if (pipelineRef == null || pipelineRef.isBlank()) {
            log.warn("SUB_PIPELINE node {} missing pipelineRef in params", node.getId());
            return null;
        }
        PipelineDefinition subPipeline = config.getPipelines().get(pipelineRef);
        if (subPipeline == null) {
            log.warn("SUB_PIPELINE node {} pipelineRef '{}' not found in config", node.getId(), pipelineRef);
            return null;
        }
        ExecutionTreeNode subRoot = subPipeline.getExecutionTree();
        if (subRoot != null) {
            executeNode(subRoot, subPipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEventWait(ExecutionTreeNode node, VariableEngine variableEngine) {
        String resultVar = paramString(node, "resultVariable");
        if (resultVar != null && !resultVar.isBlank()) {
            Object existing = variableEngine.get(resultVar);
            if (existing != null) {
                return existing;
            }
        }
        log.debug("EVENT_WAIT node {}: no blocking in activity; resultVariable left unset", node.getId());
        return null;
    }

    private Object executeLlmDecision(ExecutionTreeNode node, VariableEngine variableEngine) {
        String pluginRef = paramString(node, "pluginRef");
        String promptVar = paramString(node, "promptVariable");
        String outputVar = paramString(node, "outputVariable");
        if (pluginRef == null || promptVar == null || outputVar == null) {
            log.warn("LLM_DECISION node {} missing pluginRef, promptVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(promptVar, "prompt");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("responseText", outputVar);
        return pluginInvoker.invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    private Object executeToolRouter(ExecutionTreeNode node, PipelineDefinition pipeline,
                                      VariableEngine variableEngine, String queueName) {
        String inputVar = paramString(node, "inputVariable");
        if (inputVar == null || inputVar.isBlank()) {
            log.warn("TOOL_ROUTER node {} missing inputVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(inputVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            Object caseVal = child.getParams() != null ? child.getParams().get("caseValue") : null;
            if (caseVal == null && child.getParams() != null) {
                caseVal = child.getParams().get("toolValue");
            }
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                executeNode(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        if (!node.getChildren().isEmpty()) {
            executeNode(node.getChildren().get(0), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEvaluation(ExecutionTreeNode node, VariableEngine variableEngine) {
        String evaluatorRef = paramString(node, "evaluatorRef");
        String inputVar = paramString(node, "inputVariable");
        String outputVar = paramString(node, "outputVariable");
        if (evaluatorRef == null || inputVar == null || outputVar == null) {
            log.warn("EVALUATION node {} missing evaluatorRef, inputVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(inputVar, "input");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("result", outputVar);
        outputParamToVar.put("score", outputVar);
        return pluginInvoker.invokeWithVariableMapping(evaluatorRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    private Object executeReflection(ExecutionTreeNode node, VariableEngine variableEngine) {
        String pluginRef = paramString(node, "pluginRef");
        String inputVar = paramString(node, "inputVariable");
        String outputVar = paramString(node, "outputVariable");
        if (pluginRef == null || inputVar == null || outputVar == null) {
            log.warn("REFLECTION node {} missing pluginRef, inputVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(inputVar, "prompt");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("responseText", outputVar);
        return pluginInvoker.invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    private boolean isRetryable(ExecutionTreeNode node, Throwable t) {
        Map<String, Object> params = node.getParams();
        if (params == null) return true;
        Object list = params.get("retryableErrors");
        if (!(list instanceof Collection)) return true;
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        for (Object o : (Collection<?>) list) {
            if (o != null && msg.contains(o.toString())) return true;
        }
        return false;
    }

    private static String paramString(ExecutionTreeNode node, String key) {
        Map<String, Object> params = node.getParams();
        if (params == null) return null;
        Object v = params.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private static int paramInt(ExecutionTreeNode node, String key, int defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long paramLong(ExecutionTreeNode node, String key, long defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double paramDouble(ExecutionTreeNode node, String key, double defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean isTruthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        return !val.toString().trim().isEmpty() && !"false".equalsIgnoreCase(val.toString().trim());
    }
}
