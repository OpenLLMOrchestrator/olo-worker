package com.olo.ledger;

import java.math.BigDecimal;

/** AI/cost metrics for MODEL/PLANNER nodes: token counts, cost breakdown per provider, currency. */
public final class NodeAiMetrics {
    private final Integer tokenInputCount;
    private final Integer tokenOutputCount;
    private final BigDecimal estimatedCost;
    private final BigDecimal promptCost;
    private final BigDecimal completionCost;
    private final BigDecimal totalCost;
    private final String currency;
    private final String modelName;
    private final String provider;

    public NodeAiMetrics(Integer tokenInputCount, Integer tokenOutputCount, BigDecimal estimatedCost, String modelName, String provider) {
        this(tokenInputCount, tokenOutputCount, estimatedCost, null, null, estimatedCost, null, modelName, provider);
    }

    public NodeAiMetrics(Integer tokenInputCount, Integer tokenOutputCount,
                         BigDecimal estimatedCost, BigDecimal promptCost, BigDecimal completionCost, BigDecimal totalCost,
                         String currency, String modelName, String provider) {
        this.tokenInputCount = tokenInputCount;
        this.tokenOutputCount = tokenOutputCount;
        this.estimatedCost = estimatedCost;
        this.promptCost = promptCost;
        this.completionCost = completionCost;
        this.totalCost = totalCost;
        this.currency = currency != null && !currency.isBlank() ? currency : null;
        this.modelName = modelName != null && !modelName.isBlank() ? modelName : null;
        this.provider = provider != null && !provider.isBlank() ? provider : null;
    }

    public static NodeAiMetrics of(int tokenInput, int tokenOutput, BigDecimal cost, String model, String provider) {
        return new NodeAiMetrics(tokenInput, tokenOutput, cost, null, null, cost, "USD", model, provider);
    }

    public static NodeAiMetrics none() {
        return new NodeAiMetrics(null, null, null, null, null, null, null, null, null);
    }

    public Integer getTokenInputCount() { return tokenInputCount; }
    public Integer getTokenOutputCount() { return tokenOutputCount; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public BigDecimal getPromptCost() { return promptCost; }
    public BigDecimal getCompletionCost() { return completionCost; }
    public BigDecimal getTotalCost() { return totalCost; }
    public String getCurrency() { return currency; }
    public String getModelName() { return modelName; }
    public String getProvider() { return provider; }

    public boolean isEmpty() {
        return tokenInputCount == null && tokenOutputCount == null && estimatedCost == null
                && promptCost == null && completionCost == null && totalCost == null
                && modelName == null && provider == null;
    }
}
