package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

/**
 * Java equivalent of the Rust SDK's {@code TickEntryTasklet}: parses the entry
 * channel's {@code <interval>:<ts>:<delta>} text into the {@link TickInput} the
 * {@code ticks} channel carries.
 */
public final class TickEntryTasklet
        implements Tasklet<String, Stateless, TickInput, NoChange, WallClock, Void> {

    @Override
    public String name() {
        return "tick_entry_tasklet";
    }

    @Override
    public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new Stateless();
    }

    @Override
    public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
            throws TaskletException {
        String[] parts = input.split(":", 3);
        ctx.emit(new TickInput(
                (int) parseAt(parts, 0), parseAt(parts, 1), parseAt(parts, 2)));
    }

    private static long parseAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
