package com.olo.features;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Result of resolving which features to run before and after a node. */
public final class ResolvedPrePost {

    private final List<String> preExecution;
    private final List<String> postExecution;

    public ResolvedPrePost(List<String> preExecution, List<String> postExecution) {
        this.preExecution = preExecution != null ? List.copyOf(preExecution) : List.of();
        this.postExecution = postExecution != null ? List.copyOf(postExecution) : List.of();
    }

    public List<String> getPreExecution() {
        return preExecution;
    }

    public List<String> getPostExecution() {
        return postExecution;
    }
}
