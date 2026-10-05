package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Step classes for the {@code mix bench.steps} Java fixtures — the mirror of
 * the Rust test app's {@code BenchS0..BenchS15} and {@code BenchTurn}. Each
 * chain position needs a distinct {@link Tasklet}
 * class, so the durable pass-through steps are enumerated; every one is the
 * plain {@link DurableStep} (one idempotent {@code Insert} per run, input
 * passed on), differing only in its {@code @Tasklet.Spec} name. Public static nested classes
 * so the SDK's topology builder can reflectively instantiate them.
 */
public final class BenchSteps {
    private BenchSteps() {}

    // The durable pass-through chain, indexed by task position. All steps are
    // String -> String, so the whole chain keeps one Stream<String> type.
    private static final List<Class<? extends Tasklet<String, ?, String>>> CHAIN =
            List.of(
                    S0.class, S1.class, S2.class, S3.class, S4.class, S5.class, S6.class, S7.class,
                    S8.class, S9.class, S10.class, S11.class, S12.class, S13.class, S14.class,
                    S15.class);

    /** Build {@code in -> S0 -> ... -> S{tasks-1} -> bench_sink} on {@code c}. */
    public static void chain(ChannelBuilder c, int tasks) {
        Stream<String> s = c.input(String.class, "in").into(CHAIN.get(0));
        for (int i = 1; i < tasks; i++) {
            s = s.named("edge_BenchS" + i).into(CHAIN.get(i));
        }
        s.output("out");
    }

    @Tasklet.Spec(name = "bench_s0")
    public static final class S0 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s1")
    public static final class S1 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s2")
    public static final class S2 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s3")
    public static final class S3 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s4")
    public static final class S4 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s5")
    public static final class S5 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s6")
    public static final class S6 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s7")
    public static final class S7 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s8")
    public static final class S8 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s9")
    public static final class S9 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s10")
    public static final class S10 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s11")
    public static final class S11 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s12")
    public static final class S12 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s13")
    public static final class S13 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s14")
    public static final class S14 extends DurableStep {}

    @Tasklet.Spec(name = "bench_s15")
    public static final class S15 extends DurableStep {}

    /**
     * The loop's durable first step: steps state, input passed on. With
     * {@code FLEZHA_BENCH_WAKE_AFTER_MS} set each turn arms a timer that far out, whose
     * firing sends the bench's wake marker, {@code wake:<fired_at>}.
     */
    @Tasklet.Spec(name = "bench_turn")
    public static final class Turn extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            super.run(input, ctx);
            Optional<Long> after = TestEnv.optLong("FLEZHA_BENCH_WAKE_AFTER_MS");
            if (after.isPresent()) {
                ctx.timerAfter(Duration.ofMillis(after.get()));
            }
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("wake:" + firedAt);
        }
    }
}
