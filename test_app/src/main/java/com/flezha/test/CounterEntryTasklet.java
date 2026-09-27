package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Java equivalent of the Rust SDK's {@code CounterEntryTasklet}: parses the
 * entry channel's decimal text into the delta {@link CounterTasklet} folds.
 */
@Tasklet.Spec(name = "counter_entry_tasklet")
public class CounterEntryTasklet implements Tasklet<String, Void, Long> {

    @Override
    public void run(String input, Ctx<Void, Long> ctx) throws TaskletException {
        long delta;
        try {
            delta = Long.parseLong(input);
        } catch (NumberFormatException e) {
            delta = 0L;
        }
        ctx.emit(delta);
    }
}
