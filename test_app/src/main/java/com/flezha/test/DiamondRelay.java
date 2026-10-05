package com.flezha.test;

import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * The fan-in diamond for the crash-resume e2e, the
 * Java mirror of the Rust test app's {@code diamond_steps}:
 *
 * <pre>
 *     DiamA ─┬─▶ DiamB1 ─▶ DiamB2(blocks) ─┬─▶ DiamJoin
 *            └─▶ DiamC ────────────────────┘
 * </pre>
 *
 * The join accumulates BOTH producers' outputs and is dispatched exactly once,
 * after both commit. DiamB2 blocks BEFORE its durable change + emit — one
 * level below its sibling arm, because siblings commit at one level barrier —
 * so a SIGKILL lands with A, B1 and C committed and B2 mid-run: the respawn
 * re-runs B2 alone, and the join runs once with C's JOURNALED handoff banked
 * and B2's fresh output appended.
 */
public final class DiamondRelay implements Relay {

    @Override
    public String factoryId() {
        return "diamond_steps";
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
        // Held handle wired twice = fan-out; two streams naming DiamJoin
        // (unnamed, same class) = the fan-in.
        var fan = c.input(String.class, "in")
                .into(DiamA.class)
                .sink("dfan", s -> ("a:" + s).getBytes(StandardCharsets.UTF_8));
        fan.into(DiamB1.class)
                .named("db1")
                .into(DiamB2.class)
                .sink("db2", s -> s.getBytes(StandardCharsets.UTF_8))
                .into(DiamJoin.class);
        fan.into(DiamC.class)
                .sink("dc", s -> s.getBytes(StandardCharsets.UTF_8))
                .into(DiamJoin.class)
                .sink("j_out");
    }

    /** The shared root: commits durable state and fans out unchanged. */
    @Tasklet.Spec(name = "diam_a")
    public static final class DiamA extends DurableStep {}

    /** The pass-through upper half of the blocked arm. */
    @Tasklet.Spec(name = "diam_b1")
    public static final class DiamB1 extends DurableStep {}

    /**
     * The blocked producer: parks BEFORE its durable change + emit, so the
     * kill window is deterministic — B2 is always the uncommitted producer
     * while the sibling arm committed.
     */
    @Tasklet.Spec(name = "diam_b2")
    public static final class DiamB2 extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            StepsE2eGate.blockUntilReleased();
            ctx.change(new Insert(name()));
            ctx.emit("b:" + input);
        }
    }

    /** The fast producer: committed before the SIGKILL lands. */
    @Tasklet.Spec(name = "diam_c")
    public static final class DiamC extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.change(new Insert(name()));
            ctx.emit("c:" + input);
        }
    }

    /** The join: one {@code j:}-tagged record per accumulated contribution. */
    @Tasklet.Spec(name = "diam_join")
    public static final class DiamJoin extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("j:" + input);
        }
    }
}
