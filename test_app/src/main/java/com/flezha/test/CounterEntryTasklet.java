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
 * Java equivalent of the Rust SDK's {@code CounterEntryTasklet}: parses the
 * entry channel's decimal text into the delta {@link CounterTasklet} folds.
 */
public class CounterEntryTasklet
        implements Tasklet<String, Stateless, Long, NoChange, WallClock, Void> {

    @Override
    public String name() {
        return "counter_entry_tasklet";
    }

    @Override
    public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new Stateless();
    }

    @Override
    public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
            throws TaskletException {
        long delta;
        try {
            delta = Long.parseLong(input);
        } catch (NumberFormatException e) {
            delta = 0L;
        }
        ctx.emit(delta);
    }
}
