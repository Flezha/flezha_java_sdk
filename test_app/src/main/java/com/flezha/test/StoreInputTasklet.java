package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Tasklet.Spec(name = "store_input_tasklet")
public final class StoreInputTasklet implements Tasklet<String, StoreInputTasklet.State, String> {

    @Override
    public State initState(Optional<String> cellKey) {
        return new State(new ArrayList<>(), new ArrayList<>());
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, StoreInputChange.class, ArrayList.class);
    }

    @Override
    public void run(String input, Ctx<State, String> ctx) throws TaskletException {
        ctx.change(new StoreInputChange(input));
        ctx.emit(input);
    }

    public record State(List<byte[]> binary_inputs, List<String> text_inputs) {
    }
}
