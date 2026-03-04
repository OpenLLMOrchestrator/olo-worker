package com.olo.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory execution event sink. Events are stored per runId for later retrieval by the chat UI
 * (e.g. poll GET /api/runs/{runId}/events or consume via workflow query).
 *
 * Thread-safe. Optionally evict old runs to avoid unbounded growth.
 */
public final class InMemoryExecutionEventSink implements ExecutionEventSink {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionEventSink.class);

    private final ConcurrentHashMap<String, List<ExecutionEvent>> eventsByRunId = new ConcurrentHashMap<>();
    private final int maxEventsPerRun;
    private final int maxRuns;

    /**
     * @param maxEventsPerRun max events to keep per run (0 = unbounded)
     * @param maxRuns         max run ids to keep (0 = unbounded); oldest can be evicted on insert
     */
    public InMemoryExecutionEventSink(int maxEventsPerRun, int maxRuns) {
        this.maxEventsPerRun = Math.max(0, maxEventsPerRun);
        this.maxRuns = Math.max(0, maxRuns);
    }

    /** Unbounded in-memory sink. Prefer bounded in production. */
    public static InMemoryExecutionEventSink unbounded() {
        return new InMemoryExecutionEventSink(0, 0);
    }

    @Override
    public void emit(String runId, ExecutionEvent event) {
        if (runId == null || runId.isBlank()) {
            log.trace("Execution event skipped: runId is null or blank");
            return;
        }
        if (event == null) return;
        eventsByRunId.compute(runId, (k, list) -> {
            List<ExecutionEvent> l = list != null ? list : Collections.synchronizedList(new ArrayList<>());
            if (maxEventsPerRun > 0 && l.size() >= maxEventsPerRun) return l;
            l.add(event);
            return l;
        });
        String message = event.getPayload() != null && event.getPayload().get("message") != null
                ? String.valueOf(event.getPayload().get("message"))
                : event.getLabel();
        log.info("Execution event | runId={} | eventType={} | message={}", runId, event.getEventType(), message != null ? message : "");
        if (maxRuns > 0 && eventsByRunId.size() > maxRuns) {
            evictOldestRun();
        }
    }

    /** Returns a snapshot of events for the run (read-only). */
    public List<ExecutionEvent> getEvents(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        List<ExecutionEvent> list = eventsByRunId.get(runId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Remove events for a run (e.g. after UI has consumed or TTL). */
    public void clearRun(String runId) {
        if (runId != null && !runId.isBlank()) eventsByRunId.remove(runId);
    }

    private void evictOldestRun() {
        var it = eventsByRunId.keySet().iterator();
        if (it.hasNext()) eventsByRunId.remove(it.next());
    }
}
