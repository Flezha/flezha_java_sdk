package com.flezha.sdk.control;

/** What an operator start did. */
public enum StartOutcome {
    /** The start ran the operator. */
    STARTED,
    /** The named operator was already running and was left untouched. */
    ALREADY_RUNNING
}
