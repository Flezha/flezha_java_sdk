package com.flezha.examples.timers;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * One user's session, closed once it has been quiet for a while.
 *
 * <p>{@link #onTimer} is the second entry point beside {@link #run}. It gets the same context, so a
 * timer expiry records a change and emits exactly as a message does.
 */
@Tasklet.Spec(name = "session_window")
public final class SessionWindow implements Tasklet<String, SessionWindow.SessionState, ClosedSession> {

    /** How long a session may stay quiet before it is closed. */
    private static final Duration IDLE = Duration.ofSeconds(30);

    public static final class SessionState {
        public String user = "";
        public final List<String> clicks = new ArrayList<>();
        public boolean closed;
    }

    /** The user clicked a page. */
    public record Clicked(String page) implements Change<SessionState> {
        @Override
        public void applyTo(SessionState session) {
            session.clicks.add(page);
        }
    }

    /** The idle timer closed the session. */
    public record Closed(long at) implements Change<SessionState> {
        @Override
        public void applyTo(SessionState session) {
            session.closed = true;
        }
    }

    /**
     * The deadline currently pending, so each click can retract the last one. A plain field is
     * fine here: the runtime holds one tasklet instance per cell, and a restart re-arms.
     */
    private long deadline = -1L;

    /** A new session starts out knowing its user, which is the cell's key. */
    @Override
    @Nonnull
    public SessionState initState(Optional<String> cellKey) {
        SessionState state = new SessionState();
        state.user = cellKey.orElse("");
        return state;
    }

    @Override
    public void run(String click, Ctx<SessionState, ClosedSession> ctx) throws TaskletException {
        ctx.change(new Clicked(click));

        // The pattern: retract the deadline you set last time, then set a new one. Without the
        // cancel you would accumulate one live timer per click and close the session early.
        if (deadline >= 0) {
            ctx.cancelTimer(TimerKind.PROCESSING_TIME, deadline);
        }
        deadline = ctx.timerAfter(IDLE);
    }

    @Override
    public void onTimer(TimerFiring firing, Ctx<SessionState, ClosedSession> ctx) throws TaskletException {
        deadline = -1L;
        SessionState session = ctx.state();
        int clicks = session.clicks.size();
        ctx.change(new Closed(firing.scheduledAt()));
        ctx.emit(new ClosedSession(session.user, clicks, firing.scheduledAt()));
    }
}
