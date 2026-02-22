package com.olo.annotations;

/**
 * When a feature is invoked relative to node execution.
 */
public enum FeaturePhase {
    /** Invoked before the node executes. */
    PRE,
    /** Invoked after the node executes successfully. */
    POST_SUCCESS,
    /** Invoked after the node executes with an error (exception). */
    POST_ERROR,
    /** Invoked after the node executes (success or error). */
    FINALLY,
    /** Invoked before the node and again after (success or error). */
    PRE_FINALLY
}
