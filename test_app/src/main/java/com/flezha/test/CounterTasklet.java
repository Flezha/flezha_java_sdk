package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Java equivalent of the Rust SDK's {@code CounterTasklet}. Each input is a
 * {@code Long} delta; the Tasklet records a {@link CounterChange} via
 * {@code state.apply}, and the {@code applyChange} default mutates
 * {@code state.value}.
 */
public class CounterTasklet
        implements Tasklet<Long, CounterTasklet.State, Void, CounterChange, WallClock, Void> {

    public static final class State {
        public long value;
    }

    @Override
    public String name() {
        return "counter_tasklet";
    }

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State();
    }

    @Override
    public void applyChange(State state, CounterChange change) {
        state.value += change.delta();
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, CounterChange.class);
    }

    @Override
    public void run(Long input, StateHandle<State, CounterChange> state, TaskletCtx ctx)
            throws TaskletException {
        state.apply(new CounterChange(input));
    }
}
