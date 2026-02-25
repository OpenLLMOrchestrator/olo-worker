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
     * Registers a feature instance as <b>INTERNAL</b>. Reads {@link OloFeature} from the class and stores by {@link OloFeature#name()}.
     * The instance must implement the contract(s) for its phase: {@link PreNodeCall} (PRE), {@link PostSuccessCall} (POST_SUCCESS),
     * {@link PostErrorCall} (POST_ERROR), {@link FinallyCall} (FINALLY), or {@link PreFinallyCall} (PRE_FINALLY). Legacy {@link PostNodeCall}
     * is supported as a fallback for any post phase.
     *
     * @param featureInstance object whose class is annotated with @OloFeature and implements pre/post hooks
     * @throws IllegalArgumentException if the class is not annotated with @OloFeature or name is already registered
     */
    public void register(Object featureInstance) {
        register(featureInstance, FeaturePrivilege.INTERNAL);
    }

    /**
     * Registers a feature instance with the given privilege.
     *
     * @param featureInstance object whose class is annotated with @OloFeature and implements pre/post hooks
     * @param privilege       INTERNAL (can block) or COMMUNITY (observer-only; failures are logged)
     */
    public void register(Object featureInstance, FeaturePrivilege privilege) {
        Objects.requireNonNull(featureInstance, "featureInstance");
        Objects.requireNonNull(privilege, "privilege");
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
                privilege,
                featureInstance
        );
        if (byName.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Feature already registered: " + name);
        }
    }

    /**
     * Registers an internal (kernel-privileged) feature. Same as {@link #register(Object, FeaturePrivilege)} with {@link FeaturePrivilege#INTERNAL}.
     */
    public void registerInternal(Object featureInstance) {
        register(featureInstance, FeaturePrivilege.INTERNAL);
    }

    /**
     * Registers a community (observer-only) feature. Same as {@link #register(Object, FeaturePrivilege)} with {@link FeaturePrivilege#COMMUNITY}.
     * Community features must not block execution; if they throw, the executor logs and continues.
     */
    public void registerCommunity(Object featureInstance) {
        register(featureInstance, FeaturePrivilege.COMMUNITY);
    }

    /**
     * Registers a feature with explicit metadata as INTERNAL (e.g. when not using the annotation).
     */
    public void register(String name, FeaturePhase phase, String[] applicableNodeTypes, Object featureInstance) {
        register(name, phase, applicableNodeTypes, null, FeaturePrivilege.INTERNAL, featureInstance);
    }

    /**
     * Registers a feature with explicit metadata and privilege.
     */
    public void register(String name, FeaturePhase phase, String[] applicableNodeTypes, String contractVersion, FeaturePrivilege privilege, Object featureInstance) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(privilege, "privilege");
        if (name.isBlank()) throw new IllegalArgumentException("name must be non-blank");
        FeatureEntry entry = new FeatureEntry(name, phase != null ? phase : FeaturePhase.PRE_FINALLY, applicableNodeTypes, contractVersion, privilege, featureInstance);
        if (byName.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Feature already registered: " + name);
        }
    }

    public void register(String name, FeaturePhase phase, String[] applicableNodeTypes, String contractVersion, Object featureInstance) {
        register(name, phase, applicableNodeTypes, contractVersion, FeaturePrivilege.INTERNAL, featureInstance);
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
        private final FeaturePrivilege privilege;
        private final Object instance;

        FeatureEntry(String name, FeaturePhase phase, String[] applicableNodeTypes, String contractVersion, FeaturePrivilege privilege, Object instance) {
            this.name = name;
            this.phase = phase != null ? phase : FeaturePhase.PRE_FINALLY;
            this.applicableNodeTypes = applicableNodeTypes != null ? applicableNodeTypes.clone() : new String[0];
            this.contractVersion = contractVersion;
            this.privilege = privilege != null ? privilege : FeaturePrivilege.INTERNAL;
            this.instance = instance;
        }

        public String getName() { return name; }
        public FeaturePhase getPhase() { return phase; }
        /** Contract version (e.g. 1.0) for config compatibility; null = unknown. */
        public String getContractVersion() { return contractVersion; }
        public FeaturePrivilege getPrivilege() { return privilege; }
        /** True if kernel-privileged (can block execution). */
        public boolean isInternal() { return privilege == FeaturePrivilege.INTERNAL; }
        /** True if observer-only (must not block; failures are logged). */
        public boolean isCommunity() { return privilege == FeaturePrivilege.COMMUNITY; }
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
