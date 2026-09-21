package com.flezha.test;

import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.StateHandle;

/**
 * Mirror of the Rust test app's {@code BenchCheckpointTasklet}: the same keyed
 * upsert as {@link CheckpointStoreTasklet} with no {@code commit:} input — the
 * round-trip bench drives the interval from a declared cadence instead.
 */
public final class BenchCheckpointTasklet extends KeyedStoreTasklet {

    @Override
    public String name() {
        return "bench_checkpoint_tasklet";
    }

    @Override
    public void run(String input, StateHandle<TierStoreState, TierStoreChange> state, TaskletCtx ctx)
            throws TaskletException {
        String[] kv = split(input);
        state.apply(new TierStoreChange(kv[0], kv[1]));
        ctx.emitKeyed(kv[0], kv[0] + "=" + kv[1]);
    }
}
