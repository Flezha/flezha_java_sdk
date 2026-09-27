package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code TierStoreTasklet}: upserts the latest
 * value per key and emits {@code key=value} under the natural key, so a
 * changelog sink converges to the last value per key. The value {@code poison}
 * fails every attempt, which is what drives the dead-letter e2e's park.
 */
@Tasklet.Spec(name = "tier_store_tasklet")
public final class TierStoreTasklet extends KeyedStoreTasklet {

    @Override
    public void run(String input, Ctx<TierStoreState, String> ctx) throws TaskletException {
        String[] kv = split(input);
        if ("poison".equals(kv[1])) {
            throw new TaskletException("poison value");
        }
        ctx.change(new TierStoreChange(kv[0], kv[1]));
        ctx.emitKeyed(kv[0], kv[0] + "=" + kv[1]);
    }
}
