package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Java equivalent of the Rust SDK's {@code TickEntryTasklet}: parses the entry
 * channel's {@code <interval>:<ts>:<delta>} text into the {@link TickInput} the
 * {@code ticks} channel carries.
 */
@Tasklet.Spec(name = "tick_entry_tasklet")
public final class TickEntryTasklet implements Tasklet<String, Void, TickInput> {

    @Override
    public void run(String input, Ctx<Void, TickInput> ctx) throws TaskletException {
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
