package com.olo.ledger;

import java.math.BigDecimal;

/** Execution replay metadata: temperature, top_p, seed, raw request/response, provider request id. */
public final class NodeReplayMeta {

    private final String promptHash;
    private final String modelConfigJson;
    private final String toolCallsJson;
    private final String externalPayloadRef;
    private final BigDecimal temperature;
    private final BigDecimal topP;
    private final Long seed;
    private final String rawRequestJson;
    private final String rawResponseJson;
    private final String providerRequestId;

    public NodeReplayMeta(String promptHash, String modelConfigJson, String toolCallsJson, String externalPayloadRef) {
        this(promptHash, modelConfigJson, toolCallsJson, externalPayloadRef, null, null, null, null, null, null);
    }

    public NodeReplayMeta(String promptHash, String modelConfigJson, String toolCallsJson, String externalPayloadRef,
                          BigDecimal temperature, BigDecimal topP, Long seed,
                          String rawRequestJson, String rawResponseJson, String providerRequestId) {
        this.promptHash = promptHash != null && !promptHash.isBlank() ? promptHash : null;
        this.modelConfigJson = modelConfigJson;
        this.toolCallsJson = toolCallsJson;
        this.externalPayloadRef = externalPayloadRef != null && !externalPayloadRef.isBlank() ? externalPayloadRef : null;
        this.temperature = temperature;
        this.topP = topP;
        this.seed = seed;
        this.rawRequestJson = rawRequestJson;
        this.rawResponseJson = rawResponseJson;
        this.providerRequestId = providerRequestId != null && !providerRequestId.isBlank() ? providerRequestId : null;
    }

    public static NodeReplayMeta none() {
        return new NodeReplayMeta(null, null, null, null, null, null, null, null, null, null);
    }

    public String getPromptHash() { return promptHash; }
    public String getModelConfigJson() { return modelConfigJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getExternalPayloadRef() { return externalPayloadRef; }
    public BigDecimal getTemperature() { return temperature; }
    public BigDecimal getTopP() { return topP; }
    public Long getSeed() { return seed; }
    public String getRawRequestJson() { return rawRequestJson; }
    public String getRawResponseJson() { return rawResponseJson; }
    public String getProviderRequestId() { return providerRequestId; }

    public boolean isEmpty() {
        return promptHash == null && modelConfigJson == null && toolCallsJson == null && externalPayloadRef == null
                && temperature == null && topP == null && seed == null
                && rawRequestJson == null && rawResponseJson == null && providerRequestId == null;
    }
}
