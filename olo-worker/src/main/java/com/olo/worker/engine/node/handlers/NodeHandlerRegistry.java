package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.tree.NodeType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single responsibility: map NodeType to NodeHandler.
 */
public final class NodeHandlerRegistry {

    private final Map<NodeType, NodeHandler> handlers = new EnumMap<>(NodeType.class);
    private final NodeHandler noOpHandler = new NoOpHandler();

    public NodeHandlerRegistry(List<NodeHandler> handlerList) {
        for (NodeHandler handler : handlerList) {
            for (NodeType type : handler.supportedTypes()) {
                handlers.put(type, handler);
            }
        }
    }

    public NodeHandler forType(NodeType type) {
        if (type == null) {
            return noOpHandler;
        }
        return handlers.getOrDefault(type, noOpHandler);
    }

    private static final class NoOpHandler implements NodeHandler {
        @Override
        public Set<NodeType> supportedTypes() {
            return Set.of();
        }
    }
}

