package com.olo.features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Result of resolving which features to run before and after a node (pre; postSuccess, postError, finally). */
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
