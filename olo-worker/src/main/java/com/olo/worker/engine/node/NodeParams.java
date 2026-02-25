package com.olo.worker.engine.node;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.Collection;
import java.util.Map;

/**
 * Single responsibility: read typed parameters from an execution tree node's params map.
 */
public final class NodeParams {

    private NodeParams() {
    }

    public static String paramString(ExecutionTreeNode node, String key) {
        Map<String, Object> params = node.getParams();
        if (params == null) return null;
        Object v = params.get(key);
        return v != null ? v.toString().trim() : null;
    }

    public static int paramInt(ExecutionTreeNode node, String key, int defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long paramLong(ExecutionTreeNode node, String key, long defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double paramDouble(ExecutionTreeNode node, String key, double defaultValue) {
        Map<String, Object> params = node.getParams();
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean isTruthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        return !val.toString().trim().isEmpty() && !"false".equalsIgnoreCase(val.toString().trim());
    }

    public static boolean isRetryable(ExecutionTreeNode node, Throwable t) {
        Map<String, Object> params = node.getParams();
        if (params == null) return true;
        Object list = params.get("retryableErrors");
        if (!(list instanceof Collection)) return true;
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        for (Object o : (Collection<?>) list) {
            if (o != null && msg.contains(o.toString())) return true;
        }
        return false;
    }
}
