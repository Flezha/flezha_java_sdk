package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.operative.WorkflowOperative;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code tier_commit_block_workflow}: the keyed
 * hybrid-channel workflow fixture, and the {@code commit_block_chain} twin of
 * {@code test/fixtures/reference_topologies.txt}. Task A commits durable state
 * AND emits {@code committed:<input>} under a stable key on a keyed hybrid
 * channel; task B blocks until the resumed runner sets the release env. A
 * SIGKILL lands with A's state durable and B unfinished, and the resumed
 * instance runs to completion. It declares no delivery tier: a workflow
 * commits stage by stage, and the tier is refused on one.
 */
public final class TierCommitBlockWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "tier_commit_block_workflow";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(TierCommitA.class)
                .keyed()
                .sink("mid", s -> s.getBytes(StandardCharsets.UTF_8))
                .into(CommitBlockWorkflowOperative.BlockB.class);
    }

    /** Task A: commits durable state and re-emits deterministically under a stable key. */
    public static final class TierCommitA extends DurableStep {

        @Override
        public String name() {
            return "tier_commit_a";
        }

        @Override
        public void run(String input, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new Insert(name()));
            ctx.emitKeyed("job", "committed:" + input);
        }
    }
}
