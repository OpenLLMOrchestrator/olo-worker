package com.olo.features.debug;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debugger feature: pre and post hooks for any node when the queue ends with -debug
 * and this feature is in the pipeline/global feature list. Applicable to all node types ("*").
 * Register with {@link FeatureRegistry#getInstance()}.register(new DebuggerFeature()).
 */
@OloFeature(name = "debug", phase = FeaturePhase.PRE_POST, applicableNodeTypes = { "*" })
public final class DebuggerFeature implements PreNodeCall, PostNodeCall {

    private static final Logger log = LoggerFactory.getLogger(DebuggerFeature.class);

    @Override
    public void before(NodeExecutionContext context) {
        log.info("[DEBUG] pre  nodeId={} type={} nodeType={}", context.getNodeId(), context.getType(), context.getNodeType());
    }

    @Override
    public void after(NodeExecutionContext context, Object nodeResult) {
        log.info("[DEBUG] post nodeId={} type={} nodeType={} resultPresent={}", context.getNodeId(), context.getType(), context.getNodeType(), nodeResult != null);
    }
}
