package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import java.util.Optional;

/**
 * {@code sleeping_counter}, the Java mirror of the Rust test app's: a stream
 * that sleeps when idle. {@code key:delta} inputs fold into one running total
 * per key, and every input emits {@code key=total} on the {@code totals} sink,
 * which is what the sleep e2e reads after a wake.
 */
public final class SleepingCounterStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "sleeping_counter";
    }

    @Override
    public boolean sleepWhenIdle() {
        return true;
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .keyBy(input -> input.split(":", 2)[0])
                .into(SleepingCounterTasklet.class)
                .sink("totals");
    }

    public static final class SleepingCounterTasklet
            implements Tasklet<String, CounterTasklet.State, String, CounterChange, WallClock, Void> {

        @Override
        public String name() {
            return "sleeping_counter_tasklet";
        }

        @Override
        public CounterTasklet.State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new CounterTasklet.State();
        }

        @Override
        public void applyChange(CounterTasklet.State state, CounterChange change) {
            state.value += change.delta();
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CounterTasklet.State.class, CounterChange.class);
        }

        @Override
        public void run(String input, StateHandle<CounterTasklet.State, CounterChange> state, TaskletCtx ctx)
                throws TaskletException {
            String[] parts = input.split(":", 2);
            long delta = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            state.apply(new CounterChange(delta));
            ctx.emit(parts[0] + "=" + state.get().value);
        }
    }
}
