package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StoreInputTasklet
        implements Tasklet<String, StoreInputTasklet.State, String, StoreInputChange, WallClock, Void> {

    @Override
    public String name() {
        return "store_input_tasklet";
    }

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State(new ArrayList<>(), new ArrayList<>());
    }

    @Override
    public void applyChange(State state, StoreInputChange change) {
        state.text_inputs().add(change.text());
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, StoreInputChange.class, ArrayList.class);
    }

    @Override
    public void run(String input, StateHandle<State, StoreInputChange> state, TaskletCtx ctx)
            throws TaskletException {
        state.apply(new StoreInputChange(input));
        ctx.emit(input);
    }

    public record State(List<byte[]> binary_inputs, List<String> text_inputs) {
    }
}
