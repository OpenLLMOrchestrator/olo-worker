package com.olo.planner;

/**
 * Contract: registry for resolving a planner parser (SubtreeBuilder) by name.
 * The worker calls {@link #get(String)} only; planner modules (plugins) register
 * their parsers at bootstrap via {@link #setResolver(SubtreeBuilderResolver)}.
 * No parser implementations live in the worker.
 */
public final class SubtreeBuilderRegistry {

    /** Contract name for default JSON-array step parser (toolId + prompt per step). Configs can use this; implementations register under this name. */
    public static final String DEFAULT_JSON_ARRAY_PARSER = "DEFAULT_JSON_ARRAY_PARSER";

    private static volatile SubtreeBuilderResolver resolver;

    private SubtreeBuilderRegistry() {
    }

    /**
     * Sets the resolver that provides SubtreeBuilder by name. Called by planner modules at bootstrap.
     */
    public static void setResolver(SubtreeBuilderResolver resolver) {
        SubtreeBuilderRegistry.resolver = resolver;
    }

    /**
     * Resolves a parser by name (e.g. "default", "DEFAULT_JSON_ARRAY_PARSER"). Returns null if no resolver set or name unknown.
     */
    public static SubtreeBuilder get(String name) {
        SubtreeBuilderResolver r = resolver;
        return r != null ? r.get(name) : null;
    }

    /**
     * Resolver supplied by planner modules; maps parser name to SubtreeBuilder implementation.
     */
    @FunctionalInterface
    public interface SubtreeBuilderResolver {
        SubtreeBuilder get(String name);
    }
}
