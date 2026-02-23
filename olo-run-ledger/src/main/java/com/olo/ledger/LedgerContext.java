package com.olo.ledger;

/**
 * Thread-local context for the current run id. Set by the activity at run start and cleared at run end.
 */
public final class LedgerContext {

    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();

    private LedgerContext() {
    }

    public static void setRunId(String runId) {
        RUN_ID.set(runId);
    }

    public static String getRunId() {
        return RUN_ID.get();
    }

    public static void clear() {
        RUN_ID.remove();
    }
}
