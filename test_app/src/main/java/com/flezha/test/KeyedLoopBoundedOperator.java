package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code keyed_loop_bounded}: a durable
 * PER_INPUT loop keyed on its entry edge by the text before the first
 * {@code :}, so one Cell counts one symbol's messages and emits
 * {@code <symbol>:<count>}. The shape a partition group of bounded operator members
 * takes.
 */
public final class KeyedLoopBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "keyed_loop_bounded";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
    }

    @Override
    public boolean selfTerminating() {
        return false;
    }

    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "trades")
                .keyBy(DynamicCellUnboundedOperator::key)
                .into(Count.class)
                .sink("keyed_loop_bounded_out");
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
            String symbol = DynamicCellUnboundedOperator.key(input);
            ctx.change(new Counted(1));
            ctx.emit(symbol + ":" + ctx.state().value);
        }
    }
}
