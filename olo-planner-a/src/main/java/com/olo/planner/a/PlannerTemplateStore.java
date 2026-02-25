package com.olo.planner.a;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-accessible store for the planner prompt template (designed at bootstrap).
 * Populated by {@link PlannerBootstrapContributor}; read by the engine when executing a PLANNER node.
 */
public final class PlannerTemplateStore {

    private static final Map<String, String> TEMPLATE_BY_KEY = new ConcurrentHashMap<>();
    private static final String DEFAULT_KEY = "default";

    private PlannerTemplateStore() {
    }

    /** Sets the prompt template for the default key (used when no queue-specific template is set). */
    public static void setDefaultTemplate(String template) {
        if (template != null) {
            TEMPLATE_BY_KEY.put(DEFAULT_KEY, template);
        }
    }

    /** Sets the prompt template for the given key (e.g. queue name or "tenant:queue"). */
    public static void put(String key, String template) {
        if (key != null && template != null) {
            TEMPLATE_BY_KEY.put(key, template);
        }
    }

    /** Gets the template for the given key, or the default template if not found. */
    public static String get(String key) {
        if (key != null && TEMPLATE_BY_KEY.containsKey(key)) {
            return TEMPLATE_BY_KEY.get(key);
        }
        return TEMPLATE_BY_KEY.get(DEFAULT_KEY);
    }

    /** Gets the default template. */
    public static String getDefault() {
        return TEMPLATE_BY_KEY.get(DEFAULT_KEY);
    }
}
