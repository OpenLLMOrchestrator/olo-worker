package com.olo.worker.engine.node;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.ledger.LedgerContext;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Single responsibility: dispatch execution by node type (SEQUENCE, IF, PLUGIN, etc.).
 * Uses ChildNodeRunner callbacks to run child nodes; does not run pre/post features.
 */
public final class NodeExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutionDispatcher.class);

    private final PluginInvoker pluginInvoker;
    private final PipelineConfiguration config;
    private final ExecutionType executionType;
    private final ExecutorService executor;
    private final String ledgerRunId;

    public NodeExecutionDispatcher(PluginInvoker pluginInvoker, PipelineConfiguration config,
                                   ExecutionType executionType, ExecutorService executor,
                                   String ledgerRunId) {
        this.pluginInvoker = pluginInvoker;
        this.config = config;
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.executor = executor;
        this.ledgerRunId = ledgerRunId;
    }

    /**
     * Execute this node's logic only; returns result (e.g. from PLUGIN). Does not run pre/post features.
     * Use runChild for async child execution, runChildSync for sync (e.g. inside FORK parallel tasks).
     */
    public Object dispatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                           VariableEngine variableEngine, String queueName,
                           ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) {
            log.debug("Node {} has null or unknown type; skipping", node.getId());
            return null;
        }
        return switch (type) {
            case SEQUENCE -> executeSequence(node, pipeline, variableEngine, queueName, runChild);
            case IF -> executeIf(node, pipeline, variableEngine, queueName, runChild);
            case SWITCH -> executeSwitch(node, pipeline, variableEngine, queueName, runChild);
            case ITERATOR -> executeIterator(node, pipeline, variableEngine, queueName, runChild);
            case FORK -> executeFork(node, pipeline, variableEngine, queueName, runChild, runChildSync);
            case JOIN -> executeJoin(node, pipeline, variableEngine, queueName, runChild);
            case GROUP -> executeSequence(node, pipeline, variableEngine, queueName, runChild);
            case PLUGIN -> pluginInvoker.invoke(node, variableEngine);
            case CASE -> executeCase(node, pipeline, variableEngine, queueName, runChild);
            case TRY_CATCH -> executeTryCatch(node, pipeline, variableEngine, queueName, runChild);
            case RETRY -> executeRetry(node, pipeline, variableEngine, queueName, runChild);
            case SUB_PIPELINE -> executeSubPipeline(node, pipeline, variableEngine, queueName, runChild);
            case EVENT_WAIT -> executeEventWait(node, variableEngine);
            case LLM_DECISION -> executeLlmDecision(node, variableEngine);
            case TOOL_ROUTER -> executeToolRouter(node, pipeline, variableEngine, queueName, runChild);
            case EVALUATION -> executeEvaluation(node, variableEngine);
            case REFLECTION -> executeReflection(node, variableEngine);
            case UNKNOWN -> null;
        };
    }

    private Object executeSequence(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        for (ExecutionTreeNode child : node.getChildren()) {
            runChild.run(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeIf(ExecutionTreeNode node, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String conditionVar = NodeParams.paramString(node, "conditionVariable");
        boolean condition = true;
        if (conditionVar != null && !conditionVar.isBlank()) {
            Object val = variableEngine.get(conditionVar);
            condition = NodeParams.isTruthy(val);
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (condition && !children.isEmpty()) {
            runChild.run(children.get(0), pipeline, variableEngine, queueName);
        } else if (!condition && children.size() > 1) {
            runChild.run(children.get(1), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeSwitch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String switchVar = NodeParams.paramString(node, "switchVariable");
        if (switchVar == null || switchVar.isBlank()) {
            log.warn("SWITCH node {} missing switchVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(switchVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            if (child.getType() != NodeType.CASE) continue;
            Object caseVal = child.getParams().get("caseValue");
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                runChild.run(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        log.debug("SWITCH node {} no matching CASE for value={}", node.getId(), value);
        return null;
    }

    private Object executeCase(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        for (ExecutionTreeNode child : node.getChildren()) {
            runChild.run(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeIterator(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String collectionVar = NodeParams.paramString(node, "collectionVariable");
        String itemVar = NodeParams.paramString(node, "itemVariable");
        String indexVar = NodeParams.paramString(node, "indexVariable");
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
            runChild.run(body, pipeline, variableEngine, queueName);
            index++;
        }
        return null;
    }

    private Object executeFork(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName,
                               ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        boolean runParallel = executionType == ExecutionType.ASYNC && executor != null && children.size() > 1;
        if (runParallel) {
            List<Future<?>> futures = new ArrayList<>(children.size());
            for (ExecutionTreeNode child : children) {
                Future<?> future = executor.submit(() -> {
                    if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                        LedgerContext.setRunId(ledgerRunId);
                    }
                    try {
                        runChildSync.run(child, pipeline, variableEngine, queueName);
                    } finally {
                        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                            LedgerContext.clear();
                        }
                    }
                });
                futures.add(future);
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                }
            }
        } else {
            for (ExecutionTreeNode child : children) {
                runChild.run(child, pipeline, variableEngine, queueName);
            }
        }
        return null;
    }

    private Object executeJoin(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String strategy = NodeParams.paramString(node, "mergeStrategy");
        if (strategy == null || strategy.isBlank()) {
            log.warn("JOIN node {} missing mergeStrategy in params; required (ALL, ANY, FIRST_WINS, LAST_WINS, MAJORITY, REDUCE, PLUGIN). REDUCE/PLUGIN use pluginRef (e.g. OUTPUT_REDUCER) and input/outputMappings.", node.getId());
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        switch (strategy.toUpperCase()) {
            case "ANY":
            case "FIRST_WINS":
                if (!children.isEmpty()) {
                    runChild.run(children.get(0), pipeline, variableEngine, queueName);
                }
                break;
            case "LAST_WINS":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
            case "PLUGIN":
            case "REDUCE":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                    return pluginInvoker.invoke(node, variableEngine);
                }
                break;
            case "ALL":
            case "MAJORITY":
            default:
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
        }
        return null;
    }

    private Object executeTryCatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        try {
            runChild.run(children.get(0), pipeline, variableEngine, queueName);
        } catch (Throwable t) {
            String errorVar = NodeParams.paramString(node, "errorVariable");
            if (errorVar != null && !errorVar.isBlank()) {
                variableEngine.put(errorVar, t.getMessage() != null ? t.getMessage() : t.toString());
            }
            if (children.size() > 1) {
                runChild.run(children.get(1), pipeline, variableEngine, queueName);
            } else {
                throw t;
            }
        }
        return null;
    }

    private Object executeRetry(ExecutionTreeNode node, PipelineDefinition pipeline,
                                VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            log.warn("RETRY node {} has no child", node.getId());
            return null;
        }
        int maxAttempts = NodeParams.paramInt(node, "maxAttempts", 3);
        long initialMs = NodeParams.paramLong(node, "initialIntervalMs", 0L);
        double backoffCoefficient = NodeParams.paramDouble(node, "backoffCoefficient", 2.0);
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                runChild.run(children.get(0), pipeline, variableEngine, queueName);
                return null;
            } catch (Throwable t) {
                last = t;
                if (attempt == maxAttempts) break;
                if (!NodeParams.isRetryable(node, t)) throw t;
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
                                     VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        if (config == null || config.getPipelines() == null) {
            log.warn("SUB_PIPELINE node {} has no PipelineConfiguration; skipping", node.getId());
            return null;
        }
        String pipelineRef = NodeParams.paramString(node, "pipelineRef");
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
            runChild.run(subRoot, subPipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEventWait(ExecutionTreeNode node, VariableEngine variableEngine) {
        String resultVar = NodeParams.paramString(node, "resultVariable");
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
        String pluginRef = NodeParams.paramString(node, "pluginRef");
        String promptVar = NodeParams.paramString(node, "promptVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
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
                                    VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String inputVar = NodeParams.paramString(node, "inputVariable");
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
                runChild.run(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        if (!node.getChildren().isEmpty()) {
            runChild.run(node.getChildren().get(0), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEvaluation(ExecutionTreeNode node, VariableEngine variableEngine) {
        String evaluatorRef = NodeParams.paramString(node, "evaluatorRef");
        String inputVar = NodeParams.paramString(node, "inputVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
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
        String pluginRef = NodeParams.paramString(node, "pluginRef");
        String inputVar = NodeParams.paramString(node, "inputVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
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
}
