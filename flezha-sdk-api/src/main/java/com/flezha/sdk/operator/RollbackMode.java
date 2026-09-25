package com.flezha.sdk.operator;

/**
 * Saga rollback scope chosen per bounded operator (see
 * {@link BoundedOperator#rollbackMode}). Applied when the operator terminates
 * abnormally after partial progress; irrelevant for a clean completion or an
 * operator with no compensation hooks.
 */
public enum RollbackMode {
    /**
     * Any failure undoes <strong>all</strong> completed tasklets in reverse,
     * back to the start (the classic saga). The default.
     */
    ALL_OR_NOTHING,
    /**
     * Each finished loop iteration commits (is kept); a failure undoes only the
     * in-flight iteration's completed tasklets. Within a single iteration — or
     * a no-loop pass — rollback is all-or-nothing.
     */
    KEEP_PROGRESS
}
