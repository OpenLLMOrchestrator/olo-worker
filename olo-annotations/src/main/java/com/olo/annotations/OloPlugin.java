package com.olo.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an OLO plugin. An annotation processor generates plugin JSON
 * (PluginInfo) for drag-and-drop on a canvas and variable mapping (input/output parameters).
 * Aligns with execution tree scope contractType and plugin registry.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OloPlugin {

    /** Unique plugin id (must match scope plugin id and tree node pluginRef). */
    String id();

    /** Display name for canvas and UI. */
    String displayName() default "";

    /** Contract type (e.g. MODEL_EXECUTOR, EMBEDDING). */
    String contractType();

    /** Optional description. */
    String description() default "";

    /** Category for grouping on canvas (e.g. Model, Embedding, Tool). */
    String category() default "";

    /** Optional icon or asset key for canvas. */
    String icon() default "";

    /** Input parameters for variable mapping (variable to pluginParameter). */
    OloPluginParam[] inputParameters() default {};

    /** Output parameters for variable mapping (pluginParameter to variable). */
    OloPluginParam[] outputParameters() default {};
}
