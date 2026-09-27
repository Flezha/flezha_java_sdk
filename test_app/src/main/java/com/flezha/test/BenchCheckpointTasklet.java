package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code BenchCheckpointTasklet}: the same keyed
 * upsert as {@link CheckpointStoreTasklet} with no {@code commit:} input — the
 * round-trip bench drives the interval from a declared cadence instead.
 */
@Tasklet.Spec(name = "bench_checkpoint_tasklet")
public final class BenchCheckpointTasklet extends KeyedStoreTasklet {

    @Override
    public void run(String input, Ctx<TierStoreState, String> ctx) throws TaskletException {
        String[] kv = split(input);
        ctx.change(new TierStoreChange(kv[0], kv[1]));
        ctx.emitKeyed(kv[0], kv[0] + "=" + kv[1]);
    }
}
