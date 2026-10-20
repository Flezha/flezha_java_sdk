package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/** Static-cell entry tasklet of the 2-tasklet reference relay. */
@Tasklet.Spec(name = "macro_static_entry_tasklet", cellKeys = {"a", "b", "c", "d"})
public class SugarEntry implements Tasklet<String, SugarEntryState, String> {

    /** One input the Cell has seen. */
    public record Seen(String input) implements Change<SugarEntryState> {
        @Override
        public void applyTo(SugarEntryState state) {
            state.seen.add(input);
        }
    }

    @Override
    public List<String> cellsFor(String input) {
        char c = input.isEmpty() ? ' ' : input.charAt(0);
        return switch (c) {
            case 'a' -> List.of("a");
            case 'b' -> List.of("b");
            case 'c' -> List.of("c");
            default -> List.of("d");
        };
    }

    @Override
    public void run(String input, Ctx<SugarEntryState, String> ctx) throws TaskletException {
        ctx.change(new Seen(input));
        ctx.emit(input);
    }
}
