package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.relay.Relay;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code tier_commit_block_steps}: the keyed
 * hybrid-channel steps fixture, and the {@code commit_block_chain} twin of
 * {@code test/fixtures/reference_topologies.txt}. Task A commits durable state
 * AND emits {@code committed:<input>} under a stable key on a keyed hybrid
 * channel; task B blocks until the resumed runner sets the release env. A
 * SIGKILL lands with A's state durable and B unfinished, and the resumed
 * instance runs to completion. It declares no delivery tier: a steps relay
 * commits stage by stage, and the tier is refused on one.
 */
public final class TierCommitBlockRelay implements Relay {

    @Override
    public String factoryId() {
        return "tier_commit_block_steps";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(TierCommitA.class)
                .keyed()
                .sink("mid", s -> s.getBytes(StandardCharsets.UTF_8))
                .into(CommitBlockRelay.BlockB.class);
    }

    /** Task A: commits durable state and re-emits deterministically under a stable key. */
    @Tasklet.Spec(name = "tier_commit_a")
    public static final class TierCommitA extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.change(new Insert(name()));
            ctx.emitKeyed("job", "committed:" + input);
        }
    }
}
