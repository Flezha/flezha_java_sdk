package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code keyed_loop_steps}: a durable
 * PER_INPUT loop keyed on its entry edge by the text before the first
 * {@code :}, so one Cell counts one symbol's messages and emits
 * {@code <symbol>:<count>}. The shape a partition group of steps relay members
 * takes.
 */
public final class KeyedLoopRelay implements Relay {

    @Override
    public String factoryId() {
        return "keyed_loop_steps";
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
        c.input(String.class, "trades")
                .keyBy(DynamicCellRelay::key)
                .into(Count.class)
                .output("keyed_loop_steps_out");
    }

    @Tasklet.Spec(name = "keyed_loop_count")
    public static final class Count implements Tasklet<String, Count.State, String> {

        public static final class State {
            public long value;
        }

        /** One more message counted for the Cell's symbol. */
        public record Counted(long delta) implements Change<State> {
            @Override
            public void applyTo(State state) {
                state.value += delta;
            }
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, Counted.class);
        }

        @Override
        public void run(String input, Ctx<State, String> ctx) throws TaskletException {
            String symbol = DynamicCellRelay.key(input);
            ctx.change(new Counted(1));
            ctx.emit(symbol + ":" + ctx.state().value);
        }
    }
}
