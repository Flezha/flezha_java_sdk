package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
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

    public static final class Count
            implements Tasklet<String, Count.State, String, CounterChange, WallClock, Void> {

        public static final class State {
            public long value;
        }

        @Override
        public String name() {
            return "keyed_loop_count";
        }

        @Override
        public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new State();
        }

        @Override
        public void applyChange(State state, CounterChange change) {
            state.value += change.delta();
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, CounterChange.class);
        }

        @Override
        public void run(String input, StateHandle<State, CounterChange> state, TaskletCtx ctx)
                throws TaskletException {
            String symbol = DynamicCellUnboundedOperator.key(input);
            state.apply(new CounterChange(1));
            ctx.emit(symbol + ":" + state.get().value);
        }
    }
}
