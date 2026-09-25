package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/** Tasklet backing {@link CoalescingCounterUnboundedOperator}: folds one {@link TickInput}. */
public final class TickTasklet
        implements Tasklet<TickInput, TickTasklet.State, Void, TickChange, WallClock, Void> {

    public static final class State {
        public long last;
    }

    @Override
    public String name() {
        return "tick_tasklet";
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, TickChange.class);
    }

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State();
    }

    @Override
    public void applyChange(State state, TickChange change) {
        state.last = change.delta();
    }

    @Override
    public void run(TickInput input, StateHandle<State, TickChange> state, TaskletCtx ctx)
            throws TaskletException {
        state.apply(new TickChange(input.interval(), input.ts(), input.delta()));
    }
}
