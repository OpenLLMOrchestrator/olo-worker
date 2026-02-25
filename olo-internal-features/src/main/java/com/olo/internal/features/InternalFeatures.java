package com.olo.internal.features;

import com.olo.config.OloSessionCache;
import com.olo.features.FeatureRegistry;
import com.olo.features.debug.DebuggerFeature;
import com.olo.features.metrics.MetricsFeature;
import com.olo.features.quota.QuotaContext;
import com.olo.features.quota.QuotaFeature;
import com.olo.ledger.NodeLedgerFeature;
import com.olo.ledger.RunLedger;
import com.olo.ledger.RunLevelLedgerFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers all kernel-privileged (internal) features in one place. Used by the worker at startup;
 * community features are registered separately (e.g. from config or plugins).
 */
public final class InternalFeatures {

    private static final Logger log = LoggerFactory.getLogger(InternalFeatures.class);

    private InternalFeatures() {
    }

    /**
     * Registers internal features with the given registry. Call once at worker startup after
     * session cache (and optionally run ledger) are created.
     *
     * @param registry   global feature registry
     * @param sessionCache session cache for quota lookups (required for QuotaFeature)
     * @param runLedgerOrNull run ledger if ledger is enabled, null to skip ledger features
     */
    public static void registerInternalFeatures(FeatureRegistry registry, OloSessionCache sessionCache, RunLedger runLedgerOrNull) {
        QuotaContext.setSessionCache(sessionCache);

        registry.registerInternal(new DebuggerFeature());
        log.info("Registered debug feature (pre/post logs when using -debug pipeline)");

        registry.registerInternal(new QuotaFeature());
        log.info("Registered quota feature (PRE, fail-fast on soft/hard limit from tenant config)");

        registry.registerInternal(new MetricsFeature());
        log.info("Registered metrics feature (PRE_FINALLY, lazy MeterRegistry, olo.node.executions counter)");

        if (runLedgerOrNull != null) {
            registry.registerInternal(new RunLevelLedgerFeature());
            registry.registerInternal(new NodeLedgerFeature(runLedgerOrNull));
            log.info("Registered run ledger features (ledger-run on root, ledger-node on every node); OLO_RUN_LEDGER=true");
        } else {
            log.debug("Run ledger disabled; no ledger features registered");
        }
    }

    /**
     * Clears run-level ledger state for the current run. Call when a run ends (e.g. in workflow/activity cleanup).
     */
    public static void clearLedgerForRun() {
        RunLevelLedgerFeature.clearForRun();
    }
}
