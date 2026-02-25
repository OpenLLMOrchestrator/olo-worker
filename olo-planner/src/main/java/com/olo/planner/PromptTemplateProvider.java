package com.olo.planner;

/**
 * Contract: provides prompt templates by key (e.g. queue name or "default").
 * The worker calls {@link #getTemplate(String)} only; planner modules register
 * their implementation at bootstrap via {@link #setProvider(Provider)}.
 * No template content or keys live in the worker.
 */
public final class PromptTemplateProvider {

    /** Provider implementation; planner modules supply this at bootstrap. */
    public interface Provider {
        String resolve(String key);
    }

    private static volatile Provider provider;

    private PromptTemplateProvider() {
    }

    public static void setProvider(Provider provider) {
        PromptTemplateProvider.provider = provider;
    }

    /**
     * Gets a template for the given key. Returns null if no provider or no template.
     */
    public static String getTemplate(String key) {
        Provider p = provider;
        return p != null ? p.resolve(key) : null;
    }
}
