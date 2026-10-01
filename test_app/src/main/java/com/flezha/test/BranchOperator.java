package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.operator.Operator;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code branch_steps}: the tasklet TREE.
 *
 * <pre>
 *     BranchA ─┬─▶ BranchB ─▶ BranchD
 *              └─▶ BranchC
 * </pre>
 *
 * Every tasklet commits durable state and compensates by emitting an undo
 * record, so both tree behaviours are observable at one sink: crash-resume
 * (BranchD blocks, so the respawn re-runs D alone and the sibling's {@code c:}
 * marker appears exactly once) and compensation (with {@code FLEZHA_BRANCH_FAIL}
 * armed BranchD fails, and the rollback unwinds the two branches before the root
 * they share).
 */
public final class BranchOperator implements Operator {

    static final String BRANCH_FAIL_ENV = "FLEZHA_BRANCH_FAIL";

    @Override
    public String factoryId() {
        return "branch_steps";
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
        // One channel, two consumers: THE fan-out. Both branches read exactly
        // what BranchA released on it (held handle, wired twice).
        var mid = c.input(String.class, "in")
                .into(BranchA.class)
                .sink("mid", s -> ("a:" + s).getBytes(StandardCharsets.UTF_8));
        // Fail fast: the compensation scenario is about the rollback order,
        // not the ladder that precedes it.
        mid.into(BranchB.class)
                .sink("bmid", s -> ("b:" + s).getBytes(StandardCharsets.UTF_8))
                .retryMax(1)
                .into(BranchD.class)
                .sink("d_out");
        mid.into(BranchC.class).sink("c_out");
    }

    /** The shared root. */
    @Tasklet.Spec(name = "branch_a")
    public static final class BranchA extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("undo-a");
        }
    }

    /** The deep branch's upper half. */
    @Tasklet.Spec(name = "branch_b")
    public static final class BranchB extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("undo-b");
        }
    }

    /** The sibling leaf: emits straight to the sink, and its undo does too. */
    @Tasklet.Spec(name = "branch_c")
    public static final class BranchC extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.change(new Insert(name()));
            ctx.emit("c:" + input);
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("undo-c");
        }
    }

    /** The deep leaf under BranchB: blocks (crash-resume), or fails (compensation). */
    @Tasklet.Spec(name = "branch_d")
    public static final class BranchD extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (System.getenv(BRANCH_FAIL_ENV) != null) {
                throw new TaskletException("branch_d failed");
            }
            StepsE2eGate.blockUntilReleased();
            ctx.emit("d:" + input);
        }
    }
}
