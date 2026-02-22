package com.olo.input.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Pipeline and transaction routing for the workflow.
 */
public final class Routing {

    private final String pipeline;
    private final TransactionType transactionType;
    private final String transactionId;

    @JsonCreator
    public Routing(
            @JsonProperty("pipeline") String pipeline,
            @JsonProperty("transactionType") TransactionType transactionType,
            @JsonProperty("transactionId") String transactionId) {
        this.pipeline = pipeline;
        this.transactionType = transactionType;
        this.transactionId = transactionId;
    }

    public String getPipeline() {
        return pipeline;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Routing routing = (Routing) o;
        return Objects.equals(pipeline, routing.pipeline)
                && transactionType == routing.transactionType
                && Objects.equals(transactionId, routing.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pipeline, transactionType, transactionId);
    }
}
