package com.flezha.sdk.runtime.state;

import com.flezha.sdk.runtime.cell.TimerCounters;
import com.flezha.sdk.state.TimerKind;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * What a Tasklet declares about its timers, resolved once per Tasklet
 * rather than at every invocation: the bound, the names a refusal reports, and
 * whether its clock can serve an event-time deadline at all. Carries the
 * counter set too, so opening a session costs no registry lookup.
 *
 * <p>Mirrors the Rust SDK's {@code TimerPolicy}.
 */
public record TimerPolicy(
        int maxPerCell,
        @Nonnull String tasklet,
        boolean eventTimeClock,
        @Nonnull String clock,
        @Nonnull TimerCounters counters) {

    @Nonnull
    public static TimerPolicy of(int maxPerCell, @Nonnull String tasklet, @Nonnull ClockKind clock) {
        return new TimerPolicy(
                maxPerCell, tasklet, clock == ClockKind.EVENT_TIME, clock.wireName(), TimerCounters.of(tasklet));
    }

    /**
     * Open a session over a copy of the Cell's pending set, read from {@code pending} at the
     * session's first timer call: an invocation that sets or cancels no timer reads nothing.
     */
    @Nonnull
    public TimerSession session(@Nonnull Supplier<PendingTimers> pending, long now) {
        return new TimerSession(this, pending, now);
    }

    /**
     * Open a session seeded with the removal of the timer that is firing —
     * which is what makes that removal ride the SAME durable record as the
     * effects the firing goes on to produce.
     */
    @Nonnull
    public TimerSession firingSession(@Nonnull Supplier<PendingTimers> pending, long firedAt, @Nonnull TimerKind kind) {
        TimerSession session = session(pending, firedAt);
        session.openFiring(kind, firedAt);
        return session;
    }
}
