package com.flezha.test;

import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.authoring.CellsFor;
import java.util.List;

/** Static-cell entry tasklet of the 2-tasklet reference operator. */
@Tasklet(id = "macro_static_entry_tasklet", keys = {"a", "b", "c", "d"})
public class SugarEntry {

    @CellsFor
    public List<String> cellsFor(String input) {
        char c = input.isEmpty() ? ' ' : input.charAt(0);
        return switch (c) {
            case 'a' -> List.of("a");
            case 'b' -> List.of("b");
            case 'c' -> List.of("c");
            default -> List.of("d");
        };
    }

    public String run(String input, SugarEntryStateHandle state) {
        state.pushSeen(input);
        return input;
    }
}
