package com.olo.worker.activity.tree.impl;

import com.olo.config.OloConfig;
import com.olo.executioncontext.LocalContext;

/** Resolve effective queue and LocalContext for a tenant/queue/version. */
final class TreeContextLookup {

    static final class QueueAndContext {
        final String effectiveQueue;
        final LocalContext localContext;

        QueueAndContext(String effectiveQueue, LocalContext localContext) {
            this.effectiveQueue = effectiveQueue;
            this.localContext = localContext;
        }

        static QueueAndContext empty(String effectiveQueue) {
            return new QueueAndContext(effectiveQueue, null);
        }
    }

    static String resolveEffectiveQueue(String queueName) {
        String effectiveQueue = queueName;
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            try {
                String taskQueue = io.temporal.activity.Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null && taskQueue.endsWith("-debug")) effectiveQueue = taskQueue;
            } catch (Exception ignored) { }
        }
        return effectiveQueue;
    }

    static QueueAndContext getLocalContext(String tenantId, String queueName, String requestedVersion) {
        String effectiveQueue = resolveEffectiveQueue(queueName);
        String defaultTenantId = OloConfig.normalizeTenantId(null);
        LocalContext localContext = LocalContext.forQueue(tenantId, effectiveQueue, requestedVersion);
        if (localContext == null && !defaultTenantId.equals(tenantId)) {
            localContext = LocalContext.forQueue(defaultTenantId, effectiveQueue, requestedVersion);
        }
        if (localContext == null) {
            try {
                String taskQueue = io.temporal.activity.Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null) {
                    localContext = LocalContext.forQueue(tenantId, taskQueue, requestedVersion);
                    if (localContext == null && !defaultTenantId.equals(tenantId)) {
                        localContext = LocalContext.forQueue(defaultTenantId, taskQueue, requestedVersion);
                    }
                    if (localContext != null) effectiveQueue = taskQueue;
                }
            } catch (Exception ignored) { }
        }
        return new QueueAndContext(effectiveQueue, localContext);
    }
}
