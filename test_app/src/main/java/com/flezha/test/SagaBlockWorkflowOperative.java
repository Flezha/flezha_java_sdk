package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import java.util.List;

/**
 * Durable saga workflow operative for the crash-mid-compensation e2e (§7c.3), the
 * Java mirror of the Rust test app's {@code saga_block_workflow}. Tasks A and B
 * commit durable Cell state and declare compensation hooks; task C fails,
 * triggering reverse-order rollback (compensate B, then A). Task A's
 * {@code compensate} BLOCKS on {@code FLEZHA_WORKFLOW_E2E_RELEASE} so a SIGKILL
 * lands mid-compensation — after B's compensation-done marker is durable but
 * before A's. The respawned runner (env set) ships the markers back and
 * finishes only the outstanding hook (A), skipping B.
 */
public final class SagaBlockWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "saga_block_workflow";
    }

    @Override
    public void tasklets(ChannelBuilder c) {
        var sSagaA = c.input(String.class, "in").into(SagaA.class);
        var sSagaB = sSagaA.named("s1").into(SagaB.class);
        sSagaB.named("s2").into(SagaC.class);
    }

    /**
     * Task SA: durable + compensable; its {@code compensate} BLOCKS on the release
     * env so the SIGKILL lands mid-compensation (its compensation-intent is
     * durable, its compensation-done is not until the respawn releases it).
     */
    public static final class SagaA extends DurableStep {
        @Override
        public String name() {
            return "saga_a";
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            WorkflowE2eGate.blockUntilReleased();
        }
    }

    /**
     * Task SB: durable + compensable with a FAST {@code compensate} (its
     * compensation-done marker lands before the crash), so the respawn MUST skip
     * it rather than re-run it.
     */
    public static final class SagaB extends DurableStep {
        @Override
        public String name() {
            return "saga_b";
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<SeenState, Insert> state, TaskletCtx ctx) {
            // Fast, no-op undo — the done marker is what the resume observes.
        }
    }

    /**
     * Task SC: terminal step that always fails, triggering reverse-order rollback
     * of the completed SB then SA. Itself uncompleted ⇒ never compensated.
     */
    public static final class SagaC extends StatelessStep {
        @Override
        public String name() {
            return "saga_c";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            throw new TaskletException("saga_c always fails");
        }
    }
}
