package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code CellStressOperative}: four chained
 * static-cell tasklets over eight keys. The first fans one input out to
 * every key; the rest collapse it back onto {@code p0}, so one input walks the
 * widest Cell graph the runtime has to schedule.
 */
public final class CellStressStreamOperative implements StreamOperative {

    static final List<String> STRESS_KEYS = List.of("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7");

    @Override
    public String factoryId() {
        return "cell_stress";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(StressTaskletA.class)
                .named("a_to_b")
                .into(StressTaskletB.class)
                .named("b_to_c")
                .into(StressTaskletC.class)
                .named("c_to_d")
                .into(StressTaskletD.class)
                .sink("echo");
    }

    /** Shared stateless surface of the four stress tasklets; only the fan-out differs. */
    abstract static class StressTasklet<O> implements Tasklet<String, Stateless, O, NoChange, WallClock, Void> {

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public List<String> cellKeys() {
            return STRESS_KEYS;
        }

        @Override
        public boolean cellsForOverridden() {
            return true;
        }
    }

    /** Fans every input out to all eight Cells. */
    public static final class StressTaskletA extends StressTasklet<String> {

        @Override
        public String name() {
            return "stress_a";
        }

        @Override
        public List<String> cellsFor(String input) {
            return STRESS_KEYS;
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Collapses the fan-out back onto {@code p0}. */
    public static final class StressTaskletB extends StressTasklet<String> {

        @Override
        public String name() {
            return "stress_b";
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Second single-key hop. */
    public static final class StressTaskletC extends StressTasklet<String> {

        @Override
        public String name() {
            return "stress_c";
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Terminal hop: hands the bytes to the {@code cell_stress_echo} plugin. */
    public static final class StressTaskletD extends StressTasklet<byte[]> {

        @Override
        public String name() {
            return "stress_d";
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }
}
