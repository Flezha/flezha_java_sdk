package com.flezha.sdk.runtime.state;

import javax.annotation.Nonnull;

/**
 * The clock a tasklet's {@code ctx.now()} is served from, read at registration from what the class
 * declares. The runtime evaluates it once per invocation; a tasklet never reads time itself.
 */
public enum ClockKind {
    /** Epoch milliseconds sampled once at dispatch entry: the default. */
    WALL("wall"),
    /** What the tasklet's written {@code eventTime(input)} returns for the current input. */
    EVENT_TIME("event_time"),
    /** The durable change seq, re-armed to {@code terminal + 1} on restore. */
    LOGICAL("logical");

    private final String wireName;

    ClockKind(String wireName) {
        this.wireName = wireName;
    }

    /** The clock's name in the class declaration the core judges. */
    @Nonnull
    public String wireName() {
        return wireName;
    }
}
