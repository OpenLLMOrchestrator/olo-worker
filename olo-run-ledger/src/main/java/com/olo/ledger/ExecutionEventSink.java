package com.olo.ledger;

/**
 * Sink for run-scoped execution events. Events are emitted during execution and can be
 * consumed by the chat UI for real-time, semantic step display (debuggable, readable, collapsible).
 *
 * Implementations may store in memory (e.g. {@link InMemoryExecutionEventSink}), or forward
 * to Redis/SSE for multi-instance or streaming.
 */
public interface ExecutionEventSink {

    /**
     * Emit an event for the current run. Run id is typically from {@link LedgerContext#getRunId()}.
     *
     * @param runId  run identifier (must not be null when emitting)
     * @param event  event to emit
     */
    void emit(String runId, ExecutionEvent event);
}
