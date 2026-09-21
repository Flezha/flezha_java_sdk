package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class DoNothingTasklet
        implements Tasklet<String, DoNothingTasklet.State, byte[], NoChange, WallClock, Void> {

    @Override
    public String name() {
        return "do_nothing_tasklet";
    }

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State();
    }

    @Override
    public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }

    public record State() {
    }
}
