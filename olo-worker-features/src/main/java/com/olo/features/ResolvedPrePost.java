package com.olo.features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of resolving which features to run before and after a node (pre; postSuccess, postError, finally).
 * <p>
 * <b>Feature execution order (per node)</b>
 * <ol>
 *   <li><b>Pre</b> – all features in {@link #getPreExecution()} in list order</li>
 *   <li><b>Node</b> – node logic (e.g. plugin invoke, sequence no-op)</li>
 *   <li>On success: <b>PostSuccess</b> – all features in {@link #getPostSuccessExecution()} in list order</li>
 *   <li>On error: <b>PostError</b> – all features in {@link #getPostErrorExecution()} in list order</li>
 *   <li><b>Finally</b> – all features in {@link #getFinallyExecution()} in list order (always runs after PostSuccess or PostError)</li>
 * </ol>
 * The order of feature names within each list is determined by {@link com.olo.features.FeatureAttachmentResolver}
 * (explicit node lists first, then legacy postExecution, then scope/required features).
 */
public final class ResolvedPrePost {

    private final List<String> preExecution;
    private final List<String> postSuccessExecution;
    private final List<String> postErrorExecution;
    private final List<String> finallyExecution;

    public ResolvedPrePost(
            List<String> preExecution,
            List<String> postSuccessExecution,
            List<String> postErrorExecution,
            List<String> finallyExecution) {
        this.preExecution = preExecution != null ? List.copyOf(preExecution) : List.of();
        this.postSuccessExecution = postSuccessExecution != null ? List.copyOf(postSuccessExecution) : List.of();
        this.postErrorExecution = postErrorExecution != null ? List.copyOf(postErrorExecution) : List.of();
        this.finallyExecution = finallyExecution != null ? List.copyOf(finallyExecution) : List.of();
    }

    public List<String> getPreExecution() {
        return preExecution;
    }

    /** Features to run after the node completes successfully. */
    public List<String> getPostSuccessExecution() {
        return postSuccessExecution;
    }

    /** Features to run after the node throws an exception. */
    public List<String> getPostErrorExecution() {
        return postErrorExecution;
    }

    /** Features to run after the node (success or error). */
    public List<String> getFinallyExecution() {
        return finallyExecution;
    }

    /** Legacy: union of postSuccess, postError, and finally (order: success, error, finally). For backward compatibility. */
    public List<String> getPostExecution() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String name : postSuccessExecution) {
            if (seen.add(name)) out.add(name);
        }
        for (String name : postErrorExecution) {
            if (seen.add(name)) out.add(name);
        }
        for (String name : finallyExecution) {
            if (seen.add(name)) out.add(name);
        }
        return out;
    }
}
