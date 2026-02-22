/**
 * OLO annotations: feature and UI component metadata for bootstrap and plug-and-play loading.
 * <ul>
 *   <li>{@link com.olo.annotations.OloFeature} – feature (name, phase, applicableNodeTypes); processor generates {@code META-INF/olo-features.json}</li>
 *   <li>{@link com.olo.annotations.OloPlugin} – plugin (id, contractType, input/output parameters); processor generates {@code META-INF/olo-plugins.json} for drag-and-drop canvas and variable mapping</li>
 *   <li>{@link com.olo.annotations.OloUiComponent} – UI component (id, name, category); processor generates {@code META-INF/olo-ui-components.json}</li>
 *   <li>{@link com.olo.annotations.FeatureInfo} / {@link com.olo.annotations.PluginInfo} / {@link com.olo.annotations.UiComponentInfo} – JSON DTOs for loading</li>
 * </ul>
 * Use {@code annotationProcessor project(':olo-annotations')} so the processor runs at compile time and produces the JSON resources.
 */
package com.olo.annotations;
