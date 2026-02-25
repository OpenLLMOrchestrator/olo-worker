package com.olo.features.quota;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.annotations.ResourceCleanup;
import com.olo.config.OloSessionCache;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PreNodeCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * PRE-phase feature that checks tenant quota before execution.
 * <p>
 * <b>Must only run on the root node and only once per run.</b> The activity does INCR activeWorkflows
 * before running the execution engine; this feature runs in PRE on the first SEQUENCE (root). If quota
 * is exceeded it throws before any plugin runs; DECR runs in the
 * activity's finally, so there is no drift. Do <b>not</b> attach this feature per node (e.g. via
 * node-level {@code preExecution} or {@code features})—enable it only via pipeline {@code scope.features}
 * so that it is invoked only on the root SEQUENCE. Otherwise quota would be checked (or misused) on
 * every applicable node.
 * <p>
 * Reads current usage from Redis and compares with tenant soft/hard limits from {@code tenantConfig.quota}.
 * Fail fast: throws {@link QuotaExceededException} if exceeded (no blocking).
 */
@OloFeature(name = "quota", phase = FeaturePhase.PRE, applicableNodeTypes = { "SEQUENCE" })
public final class QuotaFeature implements PreNodeCall, ResourceCleanup {

    private static final Logger log = LoggerFactory.getLogger(QuotaFeature.class);

    /** Run quota check only once per execution (on the root SEQUENCE). */
    private static final ThreadLocal<Boolean> QUOTA_CHECKED = ThreadLocal.withInitial(() -> false);

    private static final String QUOTA_KEY = "quota";
    private static final String SOFT_LIMIT_KEY = "softLimit";
    private static final String HARD_LIMIT_KEY = "hardLimit";
    /** Optional burst allowance: softLimit * (1 + BURST_FACTOR) before reject. */
    private static final double BURST_FACTOR = 0.05;

    @Override
    public void before(NodeExecutionContext context) {
        if (QUOTA_CHECKED.get()) {
            return;
        }
        QUOTA_CHECKED.set(true);
        try {
            OloSessionCache cache = QuotaContext.getSessionCache();
            if (cache == null) {
                return;
            }
            String tenantId = context.getTenantId();
            if (tenantId == null || tenantId.isBlank()) {
                return;
            }
            Map<String, Object> tenantConfig = context.getTenantConfigMap();
            if (tenantConfig == null || tenantConfig.isEmpty()) {
                return;
            }
            Object quotaObj = tenantConfig.get(QUOTA_KEY);
            if (!(quotaObj instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> quota = (Map<String, Object>) quotaObj;
            long softLimit = toLong(quota.get(SOFT_LIMIT_KEY), -1L);
            long hardLimit = toLong(quota.get(HARD_LIMIT_KEY), -1L);
            if (softLimit < 0 && hardLimit < 0) {
                return;
            }
            long usage = cache.getActiveWorkflowsCount(tenantId);
            if (hardLimit >= 0 && usage > hardLimit) {
                log.warn("Quota hard limit exceeded: tenant={} usage={} hardLimit={}", tenantId, usage, hardLimit);
                throw new QuotaExceededException(tenantId, usage, hardLimit, true);
            }
            long effectiveSoft = softLimit >= 0 ? softLimit : Long.MAX_VALUE;
            long burstAllowance = (long) (effectiveSoft * BURST_FACTOR);
            long softThreshold = effectiveSoft + burstAllowance;
            if (usage > softThreshold) {
                log.warn("Quota soft limit exceeded: tenant={} usage={} softLimit={} (burst allowed)", tenantId, usage, effectiveSoft);
                throw new QuotaExceededException(tenantId, usage, effectiveSoft, false);
            }
        } finally {
            QUOTA_CHECKED.remove();
        }
    }

    @Override
    public void onExit() {
        QUOTA_CHECKED.remove();
    }

    private static long toLong(Object o, long defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
