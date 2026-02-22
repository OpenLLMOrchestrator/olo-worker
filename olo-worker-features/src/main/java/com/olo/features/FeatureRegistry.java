package com.olo.features;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of features. Register classes annotated with {@link OloFeature};
 * look up by name or resolve features for a node (by feature ids and applicability).
 */
public final class FeatureRegistry {

    private static final FeatureRegistry INSTANCE = new FeatureRegistry();

    private final Map<String, FeatureEntry> byName = new ConcurrentHashMap<>();

    public static FeatureRegistry getInstance() {
        return INSTANCE;
    }

    private FeatureRegistry() {
    }

    /**
     * Registers a feature instance. Reads {@link OloFeature} from the class and stores by {@link OloFeature#name()}.
     * The instance must implement {@link PreNodeCall} and/or {@link PostNodeCall} according to phase.
     *
     * @param featureInstance object whose class is annotated with @OloFeature and implements pre/post hooks
     * @throws IllegalArgumentException if the class is not annotated with @OloFeature or name is already registered
     */
    public void register(Object featureInstance) {
        Objects.requireNonNull(featureInstance, "featureInstance");
        Class<?> clazz = featureInstance.getClass();
        OloFeature ann = clazz.getAnnotation(OloFeature.class);
        if (ann == null) {
            throw new IllegalArgumentException("Feature implementation must be annotated with @OloFeature: " + clazz.getName());
        }
        String name = ann.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("@OloFeature name must be non-blank: " + clazz.getName());
        }
        String contractVersion = ann.contractVersion() != null && !ann.contractVersion().isBlank() ? ann.contractVersion() : null;
        FeatureEntry entry = new FeatureEntry(
                name,
                ann.phase(),
                ann.applicableNodeTypes(),
                contractVersion,
                featureInstance
        );
        if (byName.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Feature already registered: " + name);
        }
    }

    /**
     * Registers a feature with explicit metadata (e.g. when not using the annotation).
     */
    public void register(String name, FeaturePhase phase, String[] applicableNodeTypes, Object featureInstance) {
        register(name, phase, applicableNodeTypes, null, featureInstance);
    }

    public void register(String name, FeaturePhase phase, String[] applicableNodeTypes, String contractVersion, Object featureInstance) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must be non-blank");
        FeatureEntry entry = new FeatureEntry(name, phase != null ? phase : FeaturePhase.PRE_FINALLY, applicableNodeTypes, contractVersion, featureInstance);
        if (byName.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Feature already registered: " + name);
        }
    }

    /** Returns the contract version for the feature, or null if unknown. */
    public String getContractVersion(String name) {
        FeatureEntry e = get(name);
        return e != null ? e.getContractVersion() : null;
    }

    public FeatureEntry get(String name) {
        return byName.get(name);
    }

    /**
     * Returns feature entries for the given feature names that are applicable to the given node type.
     * If a name is not registered, it is skipped. Applicability is checked via {@link FeatureEntry#appliesTo(String, String)}.
     */
    public List<FeatureEntry> getFeaturesForNode(List<String> featureNames, String nodeType, String type) {
        if (featureNames == null || featureNames.isEmpty()) return List.of();
        List<FeatureEntry> out = new ArrayList<>();
        for (String name : featureNames) {
            FeatureEntry e = byName.get(name);
            if (e != null && e.appliesTo(nodeType, type)) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    public Map<String, FeatureEntry> getAll() {
        return Collections.unmodifiableMap(byName);
    }

    /**
     * Clears all registrations (mainly for tests).
     */
    public void clear() {
        byName.clear();
    }

    /**
     * Registered feature: metadata plus the implementation instance.
     */
    public static final class FeatureEntry {
        private final String name;
        private final FeaturePhase phase;
        private final String[] applicableNodeTypes;
        private final String contractVersion;
        private final Object instance;

        FeatureEntry(String name, FeaturePhase phase, String[] applicableNodeTypes, String contractVersion, Object instance) {
            this.name = name;
            this.phase = phase != null ? phase : FeaturePhase.PRE_FINALLY;
            this.applicableNodeTypes = applicableNodeTypes != null ? applicableNodeTypes.clone() : new String[0];
            this.contractVersion = contractVersion;
            this.instance = instance;
        }

        public String getName() { return name; }
        public FeaturePhase getPhase() { return phase; }
        /** Contract version (e.g. 1.0) for config compatibility; null = unknown. */
        public String getContractVersion() { return contractVersion; }
        public String[] getApplicableNodeTypes() { return applicableNodeTypes.length == 0 ? applicableNodeTypes : applicableNodeTypes.clone(); }
        public Object getInstance() { return instance; }

        public boolean isPre() { return phase == FeaturePhase.PRE || phase == FeaturePhase.PRE_FINALLY; }
        /** Legacy: true if feature runs in any post phase. */
        public boolean isPost() { return isPostSuccess() || isPostError() || isFinally(); }
        public boolean isPostSuccess() { return phase == FeaturePhase.POST_SUCCESS || phase == FeaturePhase.PRE_FINALLY; }
        public boolean isPostError() { return phase == FeaturePhase.POST_ERROR || phase == FeaturePhase.PRE_FINALLY; }
        public boolean isFinally() { return phase == FeaturePhase.FINALLY || phase == FeaturePhase.PRE_FINALLY; }

        public boolean appliesTo(String nodeType, String type) {
            if (applicableNodeTypes.length == 0) return true;
            String t = nodeType != null ? nodeType : "";
            String ty = type != null ? type : "";
            for (String pattern : applicableNodeTypes) {
                if (pattern == null) continue;
                if ("*".equals(pattern.trim())) return true;
                if (pattern.endsWith(".*")) {
                    String prefix = pattern.substring(0, pattern.length() - 2);
                    if (t.startsWith(prefix) || ty.startsWith(prefix)) return true;
                } else if (pattern.equals(t) || pattern.equals(ty)) {
                    return true;
                }
            }
            return false;
        }
    }
}
