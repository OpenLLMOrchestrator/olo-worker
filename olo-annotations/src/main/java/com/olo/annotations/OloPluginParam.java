package com.olo.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines a single input or output parameter for a plugin (for variable mapping and canvas UI).
 * Used inside {@link OloPlugin#inputParameters()} and {@link OloPlugin#outputParameters()}.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface OloPluginParam {

    /** Parameter name (e.g. "prompt", "responseText"). */
    String name();

    /** Type identifier (e.g. "STRING", "ARRAY", "OBJECT"). */
    String type() default "STRING";

    /** Whether the parameter is required for input; for output typically false. */
    boolean required() default false;
}
