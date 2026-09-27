package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Mirror of the Rust test app's {@code outbox_block_bounded}: a bounded operator
 * output's identity. Task A commits durable state AND emits once, keyed
 * {@code k} with one metadata pair, on a keyed hybrid channel; the output rides
 * A's cell record and reaches the sink once A's stage commits. Task B blocks, so
 * a SIGKILL lands with A committed and B unfinished.
 */
public final class OutboxBlockBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "outbox_block_bounded";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(OutboxA.class)
                .keyed()
                .sink("mid", s -> ("a-out:" + s).getBytes(StandardCharsets.UTF_8))
                .into(CommitBlockBoundedOperator.BlockB.class);
    }

    /** Task A: commits idempotent durable state and emits keyed with one metadata pair. */
    @Tasklet.Spec(name = "commit_a")
    public static final class OutboxA extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.change(new Insert(name()));
            ctx.emitKeyedWithMeta("k", input, Map.of("trace", "t1"));
        }
    }
}
