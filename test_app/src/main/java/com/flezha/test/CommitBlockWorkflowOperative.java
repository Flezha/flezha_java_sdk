package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;

/**
 * Durable structured workflow operative for the crash-resume e2e (§7c.3), the
 * Java mirror of the Rust test app's {@code commit_block_workflow}. Task A commits
 * idempotent Cell state to the WAL, then task B blocks on
 * {@code FLEZHA_WORKFLOW_E2E_RELEASE} — so a SIGKILL of the first runner lands
 * with task A's state already durable. The respawned runner (env set) restores
 * the committed state (crash-resume) and runs to completion.
 */
public final class CommitBlockWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "commit_block_workflow";
    }

    @Override
    public void tasklets(ChannelBuilder c) {
        var sCommitA = c.input(String.class, "in").into(CommitA.class);
        sCommitA.named("mid").into(BlockB.class);
    }

    /** Task A: commits idempotent durable Cell state, then hands input to task B. */
    public static final class CommitA extends DurableStep {
        @Override
        public String name() {
            return "commit_a";
        }
    }

    /**
     * Task B: blocks a pool worker until the resumed runner sets the release
     * env, then the chain reaches quiescence (status {@code ok}). No emit —
     * completion is observed controller-side via the finished-job record.
     */
    public static final class BlockB extends StatelessStep {
        @Override
        public String name() {
            return "block_b";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            WorkflowE2eGate.blockUntilReleased();
        }
    }
}
