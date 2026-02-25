package com.olo.planner.a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.olo.bootstrap.BootstrapContext;
import com.olo.bootstrap.BootstrapContributor;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.planner.PlannerContract;
import com.olo.tools.PlannerToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the static planner design contract from pipeline config and tool descriptors,
 * then attaches it to the bootstrap context under {@link PlannerContract#KEY_PLANNER_DESIGN_CONTRACT}.
 * <p>
 * Construct with a list of {@link PlannerToolDescriptor} (e.g. from {@link com.olo.plugin.PluginManager#getInternalProviders()}
 * filtered by {@link com.olo.tools.ToolProvider}).
 */
public final class PlannerBootstrapContributor implements BootstrapContributor {

    private static final Logger log = LoggerFactory.getLogger(PlannerBootstrapContributor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<PlannerToolDescriptor> toolDescriptors;

    public PlannerBootstrapContributor(List<PlannerToolDescriptor> toolDescriptors) {
        this.toolDescriptors = toolDescriptors != null ? List.copyOf(toolDescriptors) : List.of();
    }

    @Override
    public void contribute(BootstrapContext context) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(PlannerContract.KEY_PLANNER_DESIGN_CONTRACT, buildPlannerDesignContract(context));
        String json;
        try {
            json = MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            log.warn("Planner design contract serialization failed: {}", e.getMessage());
            return;
        }
        context.putContributorData(PlannerContract.KEY_PLANNER_DESIGN_CONTRACT, json);

        String promptTemplate = buildPlannerPromptTemplate(context);
        context.putContributorData(PlannerContract.KEY_PLANNER_PROMPT_TEMPLATE, promptTemplate);
        PlannerTemplateStore.setDefaultTemplate(promptTemplate);
        PlannerARegistration.registerTemplateProvider();

        log.info("Planner design contract and prompt template attached ({} tools)", toolDescriptors.size());
    }

    /**
     * Builds the static planner prompt template with {@link PlannerContract#USER_QUERY_PLACEHOLDER}
     * and a short tool list for the LLM to reference.
     */
    private String buildPlannerPromptTemplate(BootstrapContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a task planner. Given the user query, output a JSON array of steps to run. ");
        sb.append("Each step must have \"toolId\" and \"input\" (object with \"prompt\"). ");
        sb.append("Use only the following tool ids: ");
        List<String> ids = toolDescriptors.stream()
                .map(com.olo.tools.PlannerToolDescriptor::getToolId)
                .toList();
        sb.append(String.join(", ", ids));
        sb.append(".\n\nUser query: ");
        sb.append(PlannerContract.USER_QUERY_PLACEHOLDER);
        sb.append("\n\nRespond with only a JSON array, e.g. [{\"toolId\":\"...\",\"input\":{\"prompt\":\"...\"}}]");
        return sb.toString();
    }

    private Map<String, Object> buildPlannerDesignContract(BootstrapContext context) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("pipeline", buildPipelineSection(context));
        contract.put("executionPolicy", buildExecutionPolicy());
        contract.put("tooling", buildToolingSectionAsMap());
        contract.put("governance", buildGovernanceSection(context));
        return contract;
    }

    private Map<String, Object> buildPipelineSection(BootstrapContext context) {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        PipelineConfiguration firstConfig = context.getPipelineConfigByQueue().values().stream().findFirst().orElse(null);
        String configVersion = firstConfig != null ? firstConfig.getVersion() : "v1";
        String pipelineName = "default";
        if (firstConfig != null && firstConfig.getPipelines() != null && !firstConfig.getPipelines().isEmpty()) {
            pipelineName = firstConfig.getPipelines().keySet().iterator().next();
        }
        pipeline.put("pipelineName", pipelineName);
        pipeline.put("configVersion", configVersion != null ? configVersion : "v1");
        pipeline.put("snapshotVersionId", configVersion + "-" + LocalDate.now());
        return pipeline;
    }

    private Map<String, Object> buildExecutionPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("strategyMode", "MULTI_BRANCH");
        policy.put("evaluationEnabled", true);
        policy.put("reflectionEnabled", false);
        policy.put("maxDepth", 3);
        policy.put("maxBranches", 5);
        policy.put("allowedNodeTypes", List.of("SEQUENCE", "PLUGIN", "JOIN"));
        policy.put("joinMergeStrategies", List.of("REDUCE", "CONCAT"));
        policy.put("retryAllowed", false);
        return policy;
    }

    private List<Map<String, Object>> buildAvailableToolsList() {
        List<Map<String, Object>> availableTools = new ArrayList<>();
        for (PlannerToolDescriptor d : toolDescriptors) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("toolId", d.getToolId());
            tool.put("contractType", d.getContractType());
            tool.put("description", d.getDescription());
            tool.put("category", d.getCategoryName());
            tool.put("inputSchema", d.getInputSchema());
            tool.put("outputSchema", d.getOutputSchema());
            tool.put("capabilities", d.getCapabilities());
            tool.put("estimatedCost", d.getEstimatedCost());
            tool.put("version", d.getVersion());
            availableTools.add(tool);
        }
        return availableTools;
    }

    private Map<String, Object> buildToolingSectionAsMap() {
        Map<String, Object> tooling = new LinkedHashMap<>();
        tooling.put("availableTools", buildAvailableToolsList());
        return tooling;
    }

    private Map<String, Object> buildGovernanceSection(BootstrapContext context) {
        Map<String, Object> governance = new LinkedHashMap<>();
        PipelineConfiguration firstConfig = context.getPipelineConfigByQueue().values().stream().findFirst().orElse(null);
        governance.put("restrictedTools", List.of("IMAGE_GENERATOR"));
        List<String> featureRestrictions = firstConfig != null && firstConfig.getFeatureRestrictions() != null && !firstConfig.getFeatureRestrictions().isEmpty()
                ? new ArrayList<>(firstConfig.getFeatureRestrictions())
                : new ArrayList<>(List.of("quota"));
        governance.put("featureRestrictions", featureRestrictions);
        governance.put("maxEstimatedRunCost", 5.0);
        return governance;
    }
}
