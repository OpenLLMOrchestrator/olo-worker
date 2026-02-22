package com.olo.annotations;

/**
 * When a feature is invoked relative to node execution.
 */
public enum FeaturePhase {
    /** Invoked before the node executes. */
    PRE,
    /** Invoked after the node executes. */
    POST,
    /** Invoked both before and after the node executes. */
    PRE_POST
}
