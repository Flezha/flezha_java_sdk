package com.flezha.test;

import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.StateHandle;

/**
 * Mirror of the Rust test app's {@code CheckpointStoreTasklet}: the
 * {@link TierStoreTasklet} store acknowledging only at an explicit checkpoint
 * — a {@code commit:} input closes the interval, every other input folds into it.
 */
public final class CheckpointStoreTasklet extends KeyedStoreTasklet {

    @Override
    public String name() {
        return "checkpoint_store_tasklet";
    }

    @Override
    public void run(String input, StateHandle<TierStoreState, TierStoreChange> state, TaskletCtx ctx)
            throws TaskletException {
        String[] kv = split(input);
        if ("commit".equals(kv[0])) {
            ctx.checkpoint();
            return;
        }
        state.apply(new TierStoreChange(kv[0], kv[1]));
        ctx.emitKeyed(kv[0], kv[0] + "=" + kv[1]);
    }
}
