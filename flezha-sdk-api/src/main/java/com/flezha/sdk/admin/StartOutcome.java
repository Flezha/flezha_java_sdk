package com.flezha.sdk.admin;

/** What an operator start did. */
public enum StartOutcome {
    /** The start ran the operator. */
    STARTED,
    /** The named operator was already running and was left untouched. */
    ALREADY_RUNNING
}
