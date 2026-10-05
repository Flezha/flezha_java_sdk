package com.flezha.sdk.control;

/** What a relay start did. */
public enum StartOutcome {
    /** The start ran the relay. */
    STARTED,
    /** The named relay was already running and was left untouched. */
    ALREADY_RUNNING
}
