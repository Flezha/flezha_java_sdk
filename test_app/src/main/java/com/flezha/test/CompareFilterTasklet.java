package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

public final class CompareFilterTasklet
        implements Tasklet<String, CompareFilterTasklet.State, String, NoChange, WallClock, Void> {

    @Override
    public String name() {
        return "compare_filter_tasklet";
    }

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State("default", false);
    }

    @Override
    public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
        if (state.get().comparable().equals(input)) {
            ctx.emit(input);
        }
    }

    public record State(String comparable, boolean inverted) {
    }
}
