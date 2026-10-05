package com.flezha.test;

import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Durable saga steps relay for the crash-mid-compensation e2e (§7c.3), the
 * Java mirror of the Rust test app's {@code saga_block_steps}. Tasks A and B
 * commit durable Cell state and declare compensation hooks; task C fails,
 * triggering reverse-order rollback (compensate B, then A). Task A's
 * {@code compensate} BLOCKS on {@code FLEZHA_STEPS_E2E_RELEASE} so a SIGKILL
 * lands mid-compensation — after B's compensation-done marker is durable but
 * before A's. The respawned runner (env set) ships the markers back and
 * finishes only the outstanding hook (A), skipping B.
 */
public final class SagaBlockRelay implements Relay {

    @Override
    public String factoryId() {
        return "saga_block_steps";
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
        var sSagaA = c.input(String.class, "in").into(SagaA.class);
        var sSagaB = sSagaA.named("s1").into(SagaB.class);
        sSagaB.named("s2").into(SagaC.class);
    }

    /**
     * Task SA: durable + compensable; its {@code compensate} BLOCKS on the release
     * env so the SIGKILL lands mid-compensation (its compensation-intent is
     * durable, its compensation-done is not until the respawn releases it).
     */
    @Tasklet.Spec(name = "saga_a")
    public static final class SagaA extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            StepsE2eGate.blockUntilReleased();
        }
    }

    /**
     * Task SB: durable + compensable with a FAST {@code compensate} (its
     * compensation-done marker lands before the crash), so the respawn MUST skip
     * it rather than re-run it.
     */
    @Tasklet.Spec(name = "saga_b")
    public static final class SagaB extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) {
            // Fast, no-op undo — the done marker is what the resume observes.
        }
    }

    /**
     * Task SC: terminal step that always fails, triggering reverse-order rollback
     * of the completed SB then SA. Itself uncompleted ⇒ never compensated.
     */
    @Tasklet.Spec(name = "saga_c")
    public static final class SagaC extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            throw new TaskletException("saga_c always fails");
        }
    }
}
