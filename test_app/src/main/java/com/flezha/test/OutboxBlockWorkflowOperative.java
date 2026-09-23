package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Mirror of the Rust test app's {@code outbox_block_workflow}: a workflow
 * output's identity. Task A commits durable state AND emits once, keyed
 * {@code k} with one metadata pair, on a keyed hybrid channel; the output rides
 * A's cell record and reaches the sink once A's stage commits. Task B blocks, so
 * a SIGKILL lands with A committed and B unfinished.
 */
public final class OutboxBlockWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "outbox_block_workflow";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(OutboxA.class)
                .keyed()
                .sink("mid", s -> ("a-out:" + s).getBytes(StandardCharsets.UTF_8))
                .into(CommitBlockWorkflowOperative.BlockB.class);
    }

    /** Task A: commits idempotent durable state and emits keyed with one metadata pair. */
    public static final class OutboxA extends DurableStep {
        @Override
        public String name() {
            return "commit_a";
        }

        @Override
        public void run(String input, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new Insert(name()));
            ctx.emitKeyedWithMeta("k", input, Map.of("trace", "t1"));
        }
    }
}
