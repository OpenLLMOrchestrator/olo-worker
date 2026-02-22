package com.olo.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a plug-and-play UI component. An annotation processor can generate
 * component JSON (see UiComponentInfo) for discovery and loading at runtime.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OloUiComponent {

    /** Unique component identifier. */
    String id();

    /** Display name. */
    String name() default "";

    /** Category for grouping (e.g. input, layout, action). */
    String category() default "";

    /** Optional description. */
    String description() default "";

    /** Optional icon or asset key. */
    String icon() default "";
}
