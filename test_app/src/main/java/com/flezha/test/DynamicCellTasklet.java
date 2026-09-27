package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The dynamic-mode consumer of {@link DynamicCellUnboundedOperator}. It
 * declares no keying member of any kind — the edge's {@code keyBy} is what makes
 * it dynamic — and applies a Change per input so the controller writes a WAL
 * file per lazily-created Cell. It declares no idle TTL, so every Cell is kept.
 */
@Tasklet.Spec(name = "dynamic_cell_tasklet")
public final class DynamicCellTasklet implements Tasklet<String, DynamicCellTasklet.State, byte[]> {

    public record State(List<String> seen) {}

    /** One input the Cell has seen. */
    public record Seen(String text) implements Change<State> {
        @Override
        public void applyTo(State state) {
            state.seen().add(text);
        }
    }

    @Override
    public State initState(Optional<String> cellKey) {
        return new State(new ArrayList<>());
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, Seen.class, ArrayList.class);
    }

    @Override
    public void run(String input, Ctx<State, byte[]> ctx) throws TaskletException {
        ctx.change(new Seen(input));
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
