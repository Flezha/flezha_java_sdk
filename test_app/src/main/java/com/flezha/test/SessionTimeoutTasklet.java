package com.flezha.test;

import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;

/**
 * Mirror of the Rust runner's {@code SessionTimeoutTasklet}: each input pushes
 * the idle deadline out, and the firing closes the session.
 *
 * <p>A <b>processing-time</b> timer, because the answer to "nothing has arrived"
 * cannot be an event-time one — nothing would advance the clock. The timeout is
 * read from {@code TIMER_TEST_TIMEOUT_MS} so the e2e can set it short.
 */
@Tasklet.Spec(name = "session_timeout_tasklet")
public final class SessionTimeoutTasklet implements Tasklet<String, SessionTimeoutState, byte[]> {

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(SessionTimeoutChange.class, SessionTimeoutState.class);
    }

    @Override
    public void run(String input, Ctx<SessionTimeoutState, byte[]> ctx) throws TaskletException {
        ctx.change(SessionTimeoutChange.touched(input));
        ctx.timerAfter(Duration.ofMillis(timeoutMs()));
    }

    @Override
    public void onTimer(long firedAt, TimerKind kind, Ctx<SessionTimeoutState, byte[]> ctx) throws TaskletException {
        ctx.change(SessionTimeoutChange.closed(firedAt));
        ctx.emit(("closed@" + firedAt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static long timeoutMs() {
        return TestEnv.longVar("TIMER_TEST_TIMEOUT_MS", 1_000L);
    }
}
