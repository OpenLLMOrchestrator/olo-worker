/**
 * OLO worker features: annotation-based feature registration and pre/post node call contracts.
 * <ul>
 *   <li>{@link com.olo.annotations.OloFeature} – annotate a class to register it as a feature (name, phase, applicableNodeTypes)</li>
 *   <li>{@link com.olo.features.PreNodeCall} / {@link com.olo.features.PostNodeCall} – contracts invoked before/after a tree node runs</li>
 *   <li>{@link com.olo.features.FeatureRegistry} – global registry; register feature instances and resolve by name or for a node</li>
 *   <li>{@link com.olo.features.NodeExecutionContext} – context passed to pre/post hooks</li>
 *   <li>{@link com.olo.annotations.ResourceCleanup} – implement {@link com.olo.annotations.ResourceCleanup#onExit()} to release resources at worker shutdown</li>
 * </ul>
 * Execution tree nodes (olo-worker-execution-tree) can list feature names in {@code features}; the runtime invokes registered features accordingly.
 */
package com.olo.features;
