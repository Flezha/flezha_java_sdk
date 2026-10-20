package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;

/**
 * The core's refusal of a Cell's timers when the item settles
 * ({@code cell_timers}): past the cap of two pending timers, or an event-time
 * timer under a tasklet whose clock is not event time. {@code event} asks for
 * the event-time timer, a number {@code n} for a processing-time one {@code n}
 * hours ahead; the change follows.
 */
@Tasklet.Spec(name = "timer_cap_tasklet", maxTimersPerCell = 2)
public final class TimerCapTasklet implements Tasklet<String, SessionTimeoutState, byte[]> {

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(SessionTimeoutChange.class, SessionTimeoutState.class);
    }

    @Override
    public void run(String input, Ctx<SessionTimeoutState, byte[]> ctx) throws TaskletException {
        if (input.equals("event")) {
            ctx.timerAtEventTime(1);
        } else {
            ctx.timerAfter(Duration.ofHours(Long.parseLong(input)));
        }
        ctx.change(SessionTimeoutChange.touched(input));
    }
}
