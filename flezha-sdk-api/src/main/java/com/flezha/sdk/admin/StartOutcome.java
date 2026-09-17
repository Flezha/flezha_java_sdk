package com.flezha.sdk.admin;

/** What an operative start did. */
public enum StartOutcome {
    /** The start ran the operative. */
    STARTED,
    /** The named operative was already running and was left untouched. */
    ALREADY_RUNNING
}
