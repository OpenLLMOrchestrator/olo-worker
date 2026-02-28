package com.olo.worker.engine.runtime;

import com.olo.executiontree.tree.ExecutionTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single responsibility: attach planner-generated child definitions to a runtime tree node.
 */
final class RuntimeTreeAttach {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTreeAttach.class);

    static void attach(Map<String, RuntimeNodeState> nodesById, String parentNodeId, List<ExecutionTreeNode> definitions) {
        if (parentNodeId == null || definitions == null || definitions.isEmpty()) {
            if (log.isInfoEnabled() && parentNodeId != null)
                log.info("Tree attachChildren skip | parentId={} | definitions null or empty", parentNodeId);
            return;
        }
        if (log.isInfoEnabled())
            log.info("Tree attachChildren start | parentId={} | definitionsCount={}", parentNodeId, definitions.size());
        RuntimeNodeState parent = nodesById.get(parentNodeId);
        if (parent == null) {
            if (log.isWarnEnabled()) log.warn("Tree attachChildren | parent not found | parentId={}", parentNodeId);
            return;
        }
        List<String> addedIds = new ArrayList<>();
        for (ExecutionTreeNode def : definitions) {
            if (def == null) continue;
            String id = def.getId();
            if (nodesById.containsKey(id)) continue;
            RuntimeNodeState state = new RuntimeNodeState(id, def, parentNodeId, true);
            nodesById.put(id, state);
            parent.addChildId(id);
            addedIds.add(id);
            if (log.isInfoEnabled())
                log.info("Tree node created | nodeId={} | parentId={} | type={} | displayName={} | pluginRef={}",
                        id, parentNodeId, def.getType(), def.getDisplayName(), def.getPluginRef());
        }
        if (!addedIds.isEmpty() && log.isInfoEnabled())
            log.info("Tree attachChildren | parentId={} | added={} | childIds={}", parentNodeId, addedIds.size(), addedIds);
    }
}
