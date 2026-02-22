package com.olo.executiontree;

import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.inputcontract.InputContract;
import com.olo.executiontree.inputcontract.ParameterDef;
import com.olo.executiontree.scope.PluginDef;
import com.olo.executiontree.scope.Scope;
import com.olo.executiontree.outputcontract.OutputContract;
import com.olo.executiontree.outputcontract.ResultMapping;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.executiontree.variableregistry.VariableRegistryEntry;
import com.olo.executiontree.variableregistry.VariableScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTreeConfigTest {

    private static final String SAMPLE_CONFIG_JSON = """
            {
              "version": "1.0.0",
              "executionDefaults": {
                "engine": "TEMPORAL",
                "temporal": {
                  "target": "localhost:7233",
                  "namespace": "default",
                  "taskQueuePrefix": "olo-"
                },
                "activity": {
                  "payload": {
                    "maxAccumulatedOutputKeys": 0,
                    "maxResultOutputKeys": 0
                  },
                  "defaultTimeouts": {
                    "scheduleToStartSeconds": 6000,
                    "startToCloseSeconds": 3000,
                    "scheduleToCloseSeconds": 30000
                  },
                  "retryPolicy": {
                    "maximumAttempts": 3,
                    "initialIntervalSeconds": 1,
                    "backoffCoefficient": 2,
                    "maximumIntervalSeconds": 60,
                    "nonRetryableErrors": []
                  }
                }
              },
              "pluginRestrictions": [],
              "featureRestrictions": [],
              "pipelines": {
                "ai-pipeline": {
                  "name": "ai-pipeline",
                  "workflowId": "ai-pipeline",
                  "inputContract": {
                    "strict": true,
                    "parameters": [
                      { "name": "userQuery", "type": "STRING", "required": true }
                    ]
                  },
                  "variableRegistry": [
                    { "name": "userQuery", "type": "STRING", "scope": "IN" },
                    { "name": "rawResponse", "type": "STRING", "scope": "INTERNAL" },
                    { "name": "finalAnswer", "type": "STRING", "scope": "OUT" }
                  ],
                  "scope": {
                    "plugins": [
                      {
                        "id": "GPT4_EXECUTOR",
                        "contractType": "MODEL_EXECUTOR",
                        "inputParameters": [
                          { "name": "prompt", "type": "STRING", "required": true }
                        ],
                        "outputParameters": [
                          { "name": "responseText", "type": "STRING" }
                        ]
                      }
                    ],
                    "features": []
                  },
                  "executionTree": {
                    "id": "root",
                    "type": "SEQUENCE",
                    "children": [
                      {
                        "id": "modelNode",
                        "type": "PLUGIN",
                        "nodeType": "MODEL_EXECUTOR",
                        "pluginRef": "GPT4_EXECUTOR",
                        "inputMappings": [
                          { "pluginParameter": "prompt", "variable": "userQuery" }
                        ],
                        "outputMappings": [
                          { "pluginParameter": "responseText", "variable": "finalAnswer" }
                        ]
                      }
                    ]
                  },
                "outputContract": {
                  "parameters": [
                    { "name": "answer", "type": "STRING" }
                  ]
                },
                "resultMapping": [
                  { "variable": "finalAnswer", "outputParameter": "answer" }
                ]
              }
            }
            }
            """;

    @Test
    void fromJson_parsesPipelineConfiguration() {
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(SAMPLE_CONFIG_JSON);

        assertEquals("1.0.0", config.getVersion());
        assertNotNull(config.getPluginRestrictions());
        assertTrue(config.getPluginRestrictions().isEmpty());
        assertNotNull(config.getFeatureRestrictions());
        assertTrue(config.getFeatureRestrictions().isEmpty());
        assertEquals(1, config.getPipelines().size());
        assertTrue(config.getPipelines().containsKey("ai-pipeline"));

        PipelineDefinition pipeline = config.getPipelines().get("ai-pipeline");
        assertNotNull(pipeline);
        assertEquals("ai-pipeline", pipeline.getName());
        assertEquals("ai-pipeline", pipeline.getWorkflowId());

        InputContract inputContract = pipeline.getInputContract();
        assertNotNull(inputContract);
        assertTrue(inputContract.isStrict());
        assertEquals(1, inputContract.getParameters().size());
        assertEquals("userQuery", inputContract.getParameters().get(0).getName());
        assertEquals("STRING", inputContract.getParameters().get(0).getType());
        assertTrue(inputContract.getParameters().get(0).isRequired());

        assertEquals(3, pipeline.getVariableRegistry().size());
        assertEquals(VariableScope.IN, pipeline.getVariableRegistry().get(0).getScope());
        assertEquals(VariableScope.INTERNAL, pipeline.getVariableRegistry().get(1).getScope());
        assertEquals(VariableScope.OUT, pipeline.getVariableRegistry().get(2).getScope());

        Scope scope = pipeline.getScope();
        assertNotNull(scope);
        assertEquals(1, scope.getPlugins().size());
        PluginDef plugin = scope.getPlugins().get(0);
        assertEquals("GPT4_EXECUTOR", plugin.getId());
        assertEquals("MODEL_EXECUTOR", plugin.getContractType());

        ExecutionTreeNode root = pipeline.getExecutionTree();
        assertNotNull(root);
        assertEquals("root", root.getId());
        assertEquals(NodeType.SEQUENCE, root.getType());
        assertEquals(1, root.getChildren().size());

        ExecutionTreeNode modelNode = root.getChildren().get(0);
        assertEquals("modelNode", modelNode.getId());
        assertEquals(NodeType.PLUGIN, modelNode.getType());
        assertEquals("MODEL_EXECUTOR", modelNode.getNodeType());
        assertEquals("GPT4_EXECUTOR", modelNode.getPluginRef());
        assertEquals(List.of(new ParameterMapping("prompt", "userQuery")), modelNode.getInputMappings());
        assertEquals(List.of(new ParameterMapping("responseText", "finalAnswer")), modelNode.getOutputMappings());

        OutputContract outputContract = pipeline.getOutputContract();
        assertNotNull(outputContract);
        assertEquals(1, outputContract.getParameters().size());
        assertEquals("answer", outputContract.getParameters().get(0).getName());
        assertEquals("STRING", outputContract.getParameters().get(0).getType());
        assertEquals(List.of(new ResultMapping("finalAnswer", "answer")), pipeline.getResultMapping());
    }

    @Test
    void toJson_roundTrip() {
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(SAMPLE_CONFIG_JSON);
        String json = ExecutionTreeConfig.toJson(config);
        PipelineConfiguration parsed = ExecutionTreeConfig.fromJson(json);
        assertEquals(config, parsed);
    }

    @Test
    void pipelineFromJson_toJson_roundTrip() {
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(SAMPLE_CONFIG_JSON);
        PipelineDefinition pipeline = config.getPipelines().get("ai-pipeline");
        String json = ExecutionTreeConfig.toJson(pipeline);
        PipelineDefinition parsed = ExecutionTreeConfig.pipelineFromJson(json);
        assertEquals(pipeline, parsed);
    }

    @Test
    void ensureUniqueNodeIds_assignsUuidWhenIdNotValidUuid() {
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(SAMPLE_CONFIG_JSON);
        PipelineConfiguration normalized = ExecutionTreeConfig.ensureUniqueNodeIds(config);
        ExecutionTreeNode root = normalized.getPipelines().get("ai-pipeline").getExecutionTree();
        // "root" and "modelNode" are not UUIDs, so they get replaced with UUIDs
        assertNotNull(root.getId());
        assertTrue(root.getId().length() >= 32);
        assertNotEquals("root", root.getId());
        assertNotNull(root.getChildren().get(0).getId());
        assertNotEquals("modelNode", root.getChildren().get(0).getId());
        assertEquals(config.getVersion(), normalized.getVersion());
    }

    @Test
    void ensureUniqueNodeIds_keepsExistingUuid() {
        String jsonWithUuid = SAMPLE_CONFIG_JSON.replace("\"id\": \"root\"", "\"id\": \"550e8400-e29b-41d4-a716-446655440000\"");
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(jsonWithUuid);
        PipelineConfiguration normalized = ExecutionTreeConfig.ensureUniqueNodeIds(config);
        ExecutionTreeNode root = normalized.getPipelines().get("ai-pipeline").getExecutionTree();
        assertEquals("550e8400-e29b-41d4-a716-446655440000", root.getId());
    }

    @Test
    void refreshAllNodeIds_replacesEveryNodeIdWithNewUuid() {
        PipelineConfiguration config = ExecutionTreeConfig.fromJson(SAMPLE_CONFIG_JSON);
        PipelineConfiguration refreshed = ExecutionTreeConfig.refreshAllNodeIds(config);
        ExecutionTreeNode root = refreshed.getPipelines().get("ai-pipeline").getExecutionTree();
        assertNotNull(root.getId());
        assertTrue(root.getId().length() >= 32);
        assertNotEquals("root", root.getId());
        ExecutionTreeNode child = root.getChildren().get(0);
        assertNotNull(child.getId());
        assertNotEquals("modelNode", child.getId());
        // Second refresh gives different UUIDs
        PipelineConfiguration refreshed2 = ExecutionTreeConfig.refreshAllNodeIds(config);
        assertNotEquals(refreshed.getPipelines().get("ai-pipeline").getExecutionTree().getId(),
                refreshed2.getPipelines().get("ai-pipeline").getExecutionTree().getId());
    }
}
