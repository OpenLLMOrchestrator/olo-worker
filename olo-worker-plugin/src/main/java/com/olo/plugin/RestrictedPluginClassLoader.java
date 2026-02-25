package com.olo.plugin;

/**
 * Restricted parent classloader for community plugin JARs. Exposes only explicitly allowed packages;
 * all others throw {@link ClassNotFoundException}. Prevents community plugins from accessing worker
 * internals, feature registry, ledger, or kernel state. Reflection (e.g. {@code Class.forName}) is
 * not a backdoor: requests for denied classes go through this loader and throw.
 * <p>
 * <b>Allowed:</b> {@code java.*}, {@code javax.*}, {@code jakarta.*}, {@code com.olo.plugin.*},
 * {@code com.olo.config.*}, {@code org.slf4j.*}
 * <p>
 * <b>Denied (non‑exhaustive):</b> {@code com.olo.worker.*}, {@code com.olo.features.*},
 * {@code com.olo.ledger.*}, and any other internal packages. Do not add internal packages to the
 * allowed list; misconfiguration would break the security boundary.
 */
public final class RestrictedPluginClassLoader extends ClassLoader {

    private static final String JAVA_PACKAGE = "java.";
    private static final String JAVAX_PACKAGE = "javax.";
    private static final String JAKARTA_PACKAGE = "jakarta.";
    private static final String PLUGIN_PACKAGE = "com.olo.plugin";
    private static final String CONFIG_PACKAGE = "com.olo.config";
    private static final String SLF4J_PACKAGE = "org.slf4j";

    private final ClassLoader kernelLoader;

    /**
     * Creates a restricted classloader with no parent. Delegates to the loader that
     * loaded {@link PluginProvider} only for allowed package prefixes.
     */
    public RestrictedPluginClassLoader() {
        super(null);
        this.kernelLoader = PluginProvider.class.getClassLoader();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }
            if (isAllowed(name)) {
                c = kernelLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
            throw new ClassNotFoundException("Access denied: " + name
                    + " (community plugins may only use java.*, javax.*, jakarta.*, com.olo.plugin.*, com.olo.config.*, org.slf4j.*)");
        }
    }

    /**
     * Allowed: java.*, javax.*, jakarta.*, com.olo.plugin.*, com.olo.config.*, org.slf4j.*
     * Denied: com.olo.worker.*, com.olo.features.*, com.olo.ledger.*, and all other internals.
     */
    private static boolean isAllowed(String name) {
        return name.startsWith(JAVA_PACKAGE)
                || name.startsWith(JAVAX_PACKAGE)
                || name.startsWith(JAKARTA_PACKAGE)
                || name.startsWith(PLUGIN_PACKAGE)
                || name.startsWith(CONFIG_PACKAGE)
                || name.startsWith(SLF4J_PACKAGE);
    }
}
