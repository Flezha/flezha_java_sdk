package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code CellStressRelay}: four chained
 * static-cell tasklets over eight keys. The first fans one input out to
 * every key; the rest collapse it back onto {@code p0}, so one input walks the
 * widest Cell graph the runtime has to schedule.
 */
public final class CellStressRelay implements Relay {

    /** The eight static keys each stress tasklet declares in its {@code @Tasklet.Spec}. */
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
                .output("echo");
    }

    /** Shared stateless surface of the four stress tasklets; only the fan-out differs. */
    abstract static class StressTasklet<O> implements Tasklet<String, Void, O> {}

    /** Fans every input out to all eight Cells. */
    @Tasklet.Spec(name = "stress_a", keys = {"p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7"})
    public static final class StressTaskletA extends StressTasklet<String> {

        @Override
        public List<String> cellsFor(String input) {
            return STRESS_KEYS;
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Collapses the fan-out back onto {@code p0}. */
    @Tasklet.Spec(name = "stress_b", keys = {"p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7"})
    public static final class StressTaskletB extends StressTasklet<String> {

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Second single-key hop. */
    @Tasklet.Spec(name = "stress_c", keys = {"p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7"})
    public static final class StressTaskletC extends StressTasklet<String> {

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Terminal hop: hands the bytes to the {@code cell_stress_echo} plugin. */
    @Tasklet.Spec(name = "stress_d", keys = {"p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7"})
    public static final class StressTaskletD extends StressTasklet<byte[]> {

        @Override
        public List<String> cellsFor(String input) {
            return List.of("p0");
        }

        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }
}
