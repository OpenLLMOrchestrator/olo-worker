package com.olo.ledger;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.annotations.ResourceCleanup;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;

/**
 * Ledger feature that attaches only to the root node of the execution tree (first SEQUENCE).
 * Run start and run end data are persisted by the activity (before ExecutionEngine.run and in finally);
 * this feature does not duplicate that. It exists so that one feature is bound to the root for design clarity.
 * Observe-only; no-op for persistence (activity owns run-level records).
 */
@OloFeature(name = "ledger-run", phase = FeaturePhase.PRE_FINALLY, applicableNodeTypes = { "SEQUENCE" })
public final class RunLevelLedgerFeature implements PreNodeCall, PostNodeCall, ResourceCleanup {

    private static final ThreadLocal<Boolean> ROOT_VISITED = ThreadLocal.withInitial(() -> false);

    @Override
    public void before(NodeExecutionContext context) {
        if (ROOT_VISITED.get()) {
            return;
        }
        ROOT_VISITED.set(true);
        // Run start is done in activity before ExecutionEngine.run()
    }

    @Override
    public void after(NodeExecutionContext context, Object nodeResult) {
        // Run end is done in activity (finally block) with final output and status
    }

    @Override
    public void onExit() {
        ROOT_VISITED.remove();
    }

    /** Call from activity in finally block so the next run gets a fresh root check. */
    public static void clearForRun() {
        ROOT_VISITED.remove();
    }
}
