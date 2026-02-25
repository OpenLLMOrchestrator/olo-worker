package com.olo.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Central plugin manager: explicit internal registration (part of worker fat JAR) and
 * loading of community plugins from a controlled directory with a hardened classloader.
 * Only the configured directory is scanned (e.g. /opt/olo/plugins); nothing else.
 * Lives in olo-worker-plugin so all plugin-related logic stays in the plugin module.
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final List<PluginProvider> internalProviders = new ArrayList<>();
    private final List<PluginProvider> communityProviders = new ArrayList<>();
    @SuppressWarnings("unused") // keep references so classloaders are not GC'd
    private final List<ClassLoader> communityLoaders = new ArrayList<>();

    /**
     * Registers an internal plugin (must be on the worker classpath / fat JAR).
     */
    public void registerInternal(PluginProvider provider) {
        if (provider != null) {
            internalProviders.add(provider);
        }
    }

    /**
     * Loads community plugins from the given directory. Only this directory is scanned;
     * only {@code *.jar} files are loaded. Each JAR is loaded with a restricted classloader
     * that allows only: {@code java.*}, {@code javax.*}, {@code jakarta.*}, {@code com.olo.plugin.*},
     * {@code com.olo.config.*}, {@code org.slf4j.*}. Denied: {@code com.olo.worker.*},
     * {@code com.olo.features.*}, {@code com.olo.ledger.*}, and any other internal packages.
     * <p>
     * <b>Operational:</b> Community plugin load failures (classloader exception, provider
     * constructor failure, etc.) are <b>logged and skipped</b>; the worker continues. Only if
     * explicitly configured as required (e.g. {@code OLO_PLUGINS_REQUIRED=true}) would failure
     * be fatal. See docs: Plugin load failure behavior.
     *
     * @param pluginsDir path to the plugins directory (e.g. /opt/olo/plugins)
     */
    public void loadCommunityPlugins(Path pluginsDir) {
        if (pluginsDir == null) {
            return;
        }
        if (!Files.exists(pluginsDir)) {
            log.debug("Community plugins directory does not exist: {}", pluginsDir);
            return;
        }
        if (!Files.isDirectory(pluginsDir)) {
            log.warn("Community plugins path is not a directory: {}", pluginsDir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : stream) {
                loadCommunityJar(jar);
            }
        } catch (IOException e) {
            log.warn("Failed to list community plugins directory {}: {}", pluginsDir, e.getMessage());
        }
    }

    /**
     * Loads one community JAR. On any failure (classloader, ServiceLoader, provider constructor),
     * logs at error level and skips this JAR; does not throw (worker continues).
     */
    private void loadCommunityJar(Path jar) {
        try {
            URL jarUrl = jar.toUri().toURL();
            ClassLoader restrictedParent = new RestrictedPluginClassLoader();
            URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, restrictedParent);
            communityLoaders.add(loader);

            ServiceLoader<PluginProvider> serviceLoader = ServiceLoader.load(PluginProvider.class, loader);
            int n = 0;
            for (PluginProvider provider : serviceLoader) {
                try {
                    communityProviders.add(provider);
                    n++;
                } catch (Exception e) {
                    log.error("Community plugin from JAR {} failed to instantiate (skipping this provider): {}",
                            jar.getFileName(), e.getMessage(), e);
                }
            }
            if (n > 0) {
                log.info("Loaded {} provider(s) from community JAR: {}", n, jar.getFileName());
            }
        } catch (Exception e) {
            log.error("Failed to load community plugin JAR {} (skipping): {}", jar, e.getMessage(), e);
        }
    }

    /** Returns internal providers only (for registration where failure is fatal). */
    public List<PluginProvider> getInternalProviders() {
        return new ArrayList<>(internalProviders);
    }

    /** Returns community providers only (for registration where failure is log-and-skip). */
    public List<PluginProvider> getCommunityProviders() {
        return new ArrayList<>(communityProviders);
    }

    /**
     * Returns all providers: internal first, then community. Prefer {@link #getInternalProviders()}
     * and {@link #getCommunityProviders()} when registering so internal failures are fatal and
     * community failures are log-and-skip.
     */
    public List<PluginProvider> getProviders() {
        List<PluginProvider> out = new ArrayList<>(internalProviders.size() + communityProviders.size());
        out.addAll(internalProviders);
        out.addAll(communityProviders);
        return out;
    }

    /** Number of internal providers registered. */
    public int getInternalCount() {
        return internalProviders.size();
    }

    /** Number of community providers loaded from JARs. */
    public int getCommunityCount() {
        return communityProviders.size();
    }
}
