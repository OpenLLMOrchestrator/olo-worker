package com.olo.worker.engine.node;

import com.olo.features.FeatureRegistry;
import com.olo.features.FinallyCall;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PostErrorCall;
import com.olo.features.PostNodeCall;
import com.olo.features.PostSuccessCall;
import com.olo.features.PreFinallyCall;
import com.olo.features.PreNodeCall;
import com.olo.features.ResolvedPrePost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single responsibility: run pre- and post-execution feature hooks for a node.
 */
public final class NodeFeatureRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeFeatureRunner.class);

    public void runPre(ResolvedPrePost resolved, NodeExecutionContext context, FeatureRegistry registry) {
        for (String name : resolved.getPreExecution()) {
            FeatureRegistry.FeatureEntry e = registry.get(name);
            if (e == null) continue;
            Object inst = e.getInstance();
            if (inst instanceof PreNodeCall) {
                if (e.isCommunity()) {
                    try {
                        ((PreNodeCall) inst).before(context);
                    } catch (Throwable t) {
                        log.warn("Community pre feature {} failed (observer-only); continuing", name, t);
                    }
                } else {
                    ((PreNodeCall) inst).before(context);
                }
            }
        }
    }

    public void runPostSuccess(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                               FeatureRegistry registry) {
        for (String name : resolved.getPostSuccessExecution()) {
            runPostFeature(name, context, nodeResult, registry, PostPhase.SUCCESS);
        }
    }

    public void runPostError(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                            FeatureRegistry registry) {
        for (String name : resolved.getPostErrorExecution()) {
            runPostFeature(name, context, nodeResult, registry, PostPhase.ERROR);
        }
    }

    public void runFinally(ResolvedPrePost resolved, NodeExecutionContext context, Object nodeResult,
                           FeatureRegistry registry) {
        for (String name : resolved.getFinallyExecution()) {
            runPostFeature(name, context, nodeResult, registry, PostPhase.FINALLY);
        }
    }

    private enum PostPhase { SUCCESS, ERROR, FINALLY }

    private void runPostFeature(String name, NodeExecutionContext context, Object nodeResult,
                                FeatureRegistry registry, PostPhase phase) {
        FeatureRegistry.FeatureEntry e = registry.get(name);
        if (e == null) return;
        Object inst = e.getInstance();
        try {
            switch (phase) {
                case SUCCESS -> {
                    if (inst instanceof PreFinallyCall) {
                        ((PreFinallyCall) inst).afterSuccess(context, nodeResult);
                    } else if (inst instanceof PostSuccessCall) {
                        ((PostSuccessCall) inst).afterSuccess(context, nodeResult);
                    } else if (inst instanceof PostNodeCall) {
                        ((PostNodeCall) inst).after(context, nodeResult);
                    }
                }
                case ERROR -> {
                    if (inst instanceof PreFinallyCall) {
                        ((PreFinallyCall) inst).afterError(context, nodeResult);
                    } else if (inst instanceof PostErrorCall) {
                        ((PostErrorCall) inst).afterError(context, nodeResult);
                    } else if (inst instanceof PostNodeCall) {
                        ((PostNodeCall) inst).after(context, nodeResult);
                    }
                }
                case FINALLY -> {
                    if (inst instanceof PreFinallyCall) {
                        ((PreFinallyCall) inst).afterFinally(context, nodeResult);
                    } else if (inst instanceof FinallyCall) {
                        ((FinallyCall) inst).afterFinally(context, nodeResult);
                    } else if (inst instanceof PostNodeCall) {
                        ((PostNodeCall) inst).after(context, nodeResult);
                    }
                }
            }
        } catch (Throwable t) {
            log.warn("Post feature {} failed", name, t);
        }
    }
}
