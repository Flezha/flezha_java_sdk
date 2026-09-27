package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code CheckpointStoreTasklet}: the
 * {@link TierStoreTasklet} store acknowledging only at an explicit checkpoint
 * — a {@code commit:} input closes the interval, every other input folds into it.
 */
@Tasklet.Spec(name = "checkpoint_store_tasklet")
public final class CheckpointStoreTasklet extends KeyedStoreTasklet {

    @Override
    public void run(String input, Ctx<TierStoreState, String> ctx) throws TaskletException {
        String[] kv = split(input);
        if ("commit".equals(kv[0])) {
            ctx.checkpoint();
            return;
        }
        ctx.change(new TierStoreChange(kv[0], kv[1]));
        ctx.emitKeyed(kv[0], kv[0] + "=" + kv[1]);
    }
}
