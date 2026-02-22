package com.olo.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a feature that can be registered and invoked during execution tree traversal.
 * Use with a feature registry to register; implement pre/post call contracts depending on {@link #phase()}.
 * <p>
 * An annotation processor can generate feature JSON (see {@link FeatureInfo}) for bootstrap loading.
 * {@link #applicableNodeTypes()} supports exact match, prefix ("MODAL.*"), and "*" for all nodes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OloFeature {

    /** Unique feature identifier (used in node's features list and in the registry). */
    String name();

    /** Feature contract version (e.g. 1.0) for compatibility checks; config scope can require this version. */
    String contractVersion() default "1.0";

    /** When to invoke: PRE, POST_SUCCESS, POST_ERROR, FINALLY, or PRE_FINALLY (before and after). */
    FeaturePhase phase() default FeaturePhase.PRE_FINALLY;

    /** Node type patterns this feature applies to. Empty = all. E.g. "MODAL.*", "PLANNER.*", "GROUP", "IF", "*". */
    String[] applicableNodeTypes() default { };
}
