package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code static_cell_steps}: a PER_INPUT loop
 * whose one tasklet declares three static cells and routes every message to two
 * of them; each cell that runs emits {@code <cell key>:<message>}, so one
 * message yields exactly two outputs.
 */
public final class StaticCellSteps implements Operator {

    @Override
    public String factoryId() {
        return "static_cell_steps";
    }

    @Override
    public Run run() {
        return Run.steps(Each.PER_INPUT);
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(Keyed.class).sink("out");
    }

    /** Emits its cell key beside the message, on the two cells it names. */
    @Tasklet.Spec(name = "static_cell_step", keys = {"a", "b", "c"})
    public static final class Keyed extends NoStateStep {
        @Override
        public List<String> cellsFor(String input) {
            return List.of("a", "b");
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(ctx.cellKey().orElse("") + ":" + input);
        }
    }
}
