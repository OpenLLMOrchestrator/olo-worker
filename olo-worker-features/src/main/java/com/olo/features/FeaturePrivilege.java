package com.olo.features;

/**
 * Privilege level of a feature. Determines what the feature is allowed to do and how the executor treats failures.
 * <p>
 * <b>INTERNAL (kernel-privileged):</b> Olo-controlled, part of the fat JAR. Can block execution, mutate context,
 * affect failure semantics, persist ledger entries, enforce quotas, inject audit behavior, run in any phase.
 * Examples: Quota, Ledger, deterministic guard, compliance, security policy. If an internal feature throws,
 * the executor propagates the exception (execution fails).
 * <p>
 * <b>COMMUNITY (restricted):</b> Observer-class only. Can read {@link NodeExecutionContext}, log, emit metrics,
 * append attributes. Cannot block execution, modify execution plan, throw policy exceptions, override failure
 * semantics. If a community feature throws, the executor catches and logs; execution continues.
 */
public enum FeaturePrivilege {

    /** Kernel-privileged; can block execution and affect failure semantics. */
    INTERNAL,

    /** Observer only; cannot block; failures are logged and execution continues. */
    COMMUNITY
}
