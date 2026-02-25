package com.olo.features;

/**
 * Contract for feature logic that runs before a tree node and again after (success, error, and finally).
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#PRE_FINALLY}.
 * Use {@link #afterSuccess} / {@link #afterError} for <b>heavy lifting</b> (exception-prone, success-vs-error);
 * use {@link #afterFinally} for <b>non–exception-prone</b> logic (logging, metrics, cleanup). Alternatively
 * implement {@link PreNodeCall} plus {@link PostSuccessCall}, {@link PostErrorCall}, and/or {@link FinallyCall} as needed.
 */
public interface PreFinallyCall extends PreNodeCall {

    /**
     * Called after the node has executed successfully.
     *
     * @param context    node context
     * @param nodeResult result of the node execution (non-null on success)
     */
    void afterSuccess(NodeExecutionContext context, Object nodeResult);

    /**
     * Called after the node has executed with an error (exception).
     *
     * @param context    node context
     * @param nodeResult result (often null when error)
     */
    void afterError(NodeExecutionContext context, Object nodeResult);

    /**
     * Called after the node has completed (success or error). Always runs after postSuccess or postError.
     *
     * @param context    node context
     * @param nodeResult result (may be null if node threw)
     */
    void afterFinally(NodeExecutionContext context, Object nodeResult);
}
