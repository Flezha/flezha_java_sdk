package com.flezha.sdk.state;

import javax.annotation.Nonnull;

/**
 * One timer firing, as {@code onTimer} receives it: the clock it was scheduled against and the
 * timestamp it was scheduled at, never the moment it actually fired.
 */
public record TimerFiring(@Nonnull TimerKind kind, long scheduledAt) {}
