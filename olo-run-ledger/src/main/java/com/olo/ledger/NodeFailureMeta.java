package com.olo.ledger;

/**
 * Failure and retry metadata: error_code, error_details (stacktrace etc.), attempt_number, max_attempts, backoff_ms.
 */
public final class NodeFailureMeta {

    private final Integer retryCount;
    private final String executionStage;
    private final String failureType;
    private final String errorCode;
    private final String errorDetailsJson;
    private final Integer attemptNumber;
    private final Integer maxAttempts;
    private final Long backoffMs;

    /** Backward-compat: error_code, errorDetailsJson, attemptNumber, maxAttempts, backoffMs null. */
    public NodeFailureMeta(Integer retryCount, String executionStage, String failureType) {
        this(retryCount, executionStage, failureType, null, null, null, null, null);
    }

    public NodeFailureMeta(Integer retryCount, String executionStage, String failureType,
                           String errorCode, String errorDetailsJson,
                           Integer attemptNumber, Integer maxAttempts, Long backoffMs) {
        this.retryCount = retryCount;
        this.executionStage = executionStage != null && !executionStage.isBlank() ? executionStage : null;
        this.failureType = failureType != null && !failureType.isBlank() ? failureType : null;
        this.errorCode = errorCode != null && !errorCode.isBlank() ? errorCode : null;
        this.errorDetailsJson = errorDetailsJson;
        this.attemptNumber = attemptNumber;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

    public static NodeFailureMeta none() {
        return new NodeFailureMeta(null, null, null, null, null, null, null, null);
    }

    public Integer getRetryCount() { return retryCount; }
    public String getExecutionStage() { return executionStage; }
    public String getFailureType() { return failureType; }
    public String getErrorCode() { return errorCode; }
    public String getErrorDetailsJson() { return errorDetailsJson; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public Long getBackoffMs() { return backoffMs; }

    public boolean isEmpty() {
        return retryCount == null && executionStage == null && failureType == null
                && errorCode == null && errorDetailsJson == null
                && attemptNumber == null && maxAttempts == null && backoffMs == null;
    }
}
