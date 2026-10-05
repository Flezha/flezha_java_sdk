package com.flezha.test;

import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Durable structured steps relay for the crash-resume e2e (§7c.3), the
 * Java mirror of the Rust test app's {@code commit_block_steps}. Task A commits
 * idempotent Cell state to the WAL, then task B blocks on
 * {@code FLEZHA_STEPS_E2E_RELEASE} — so a SIGKILL of the first runner lands
 * with task A's state already durable. The respawned runner (env set) restores
 * the committed state (crash-resume) and runs to completion.
 */
public final class CommitBlockRelay implements Relay {

    @Override
    public String factoryId() {
        return "commit_block_steps";
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
        var sCommitA = c.input(String.class, "in").into(CommitA.class);
        sCommitA.named("mid").into(BlockB.class);
    }

    /** Task A: commits idempotent durable Cell state, then hands input to task B. */
    @Tasklet.Spec(name = "commit_a")
    public static final class CommitA extends DurableStep {}

    /**
     * Task B: blocks a pool worker until the resumed runner sets the release
     * env, then the chain reaches quiescence (status {@code ok}). No emit —
     * completion is observed controller-side via the finished-job record.
     */
    @Tasklet.Spec(name = "block_b")
    public static final class BlockB extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            StepsE2eGate.blockUntilReleased();
        }
    }
}
