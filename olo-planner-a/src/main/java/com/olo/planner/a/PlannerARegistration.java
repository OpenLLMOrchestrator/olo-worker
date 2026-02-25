package com.olo.planner.a;

import com.olo.planner.SubtreeBuilder;
import com.olo.planner.SubtreeBuilderRegistry;
import com.olo.planner.PromptTemplateProvider;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers olo-planner-a implementations with the planner contract (SubtreeBuilderRegistry, PromptTemplateProvider).
 * Called at class load so the worker resolves parsers and templates via contract only; no planner logic in worker.
 */
public final class PlannerARegistration {

    private static final Map<String, SubtreeBuilder> PARSERS = new ConcurrentHashMap<>();
    private static final String DEFAULT_NAME = "default";

    static {
        SubtreeBuilder defaultParser = new JsonStepsSubtreeBuilder();
        PARSERS.put(DEFAULT_NAME, defaultParser);
        PARSERS.put(SubtreeBuilderRegistry.DEFAULT_JSON_ARRAY_PARSER, defaultParser);
        SubtreeBuilderRegistry.setResolver(name -> {
            if (name == null || name.isBlank()) return PARSERS.get(DEFAULT_NAME);
            return PARSERS.getOrDefault(name, PARSERS.get(DEFAULT_NAME));
        });
    }

    /** Plugin id for the default JSON steps subtree creator (SUBTREE_CREATOR contract). */
    public static final String DEFAULT_JSON_SUBTREE_CREATOR = "DEFAULT_JSON_SUBTREE_CREATOR";

    /**
     * Registers the template provider so the worker can resolve templates by key. Call from bootstrap after templates are set.
     */
    public static void registerTemplateProvider() {
        PromptTemplateProvider.setProvider(PlannerTemplateStore::get);
    }

    /**
     * Registers the default subtree-creator plugin for the given tenants so PLANNER nodes
     * can use subtreeCreatorPluginRef to build execution subtrees from plan text.
     */
    public static void registerSubtreeCreatorPlugin(PluginRegistry registry, List<String> tenantIds) {
        if (registry == null || tenantIds == null || tenantIds.isEmpty()) return;
        ExecutablePlugin plugin = new JsonStepsSubtreeCreatorPlugin();
        for (String tenantId : tenantIds) {
            registry.registerSubtreeCreator(tenantId, DEFAULT_JSON_SUBTREE_CREATOR, plugin);
        }
    }

    public static void register(String name, SubtreeBuilder builder) {
        if (name != null && builder != null) {
            PARSERS.put(name, builder);
        }
    }

    private PlannerARegistration() {
    }
}
