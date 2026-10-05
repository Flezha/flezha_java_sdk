package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * {@code sleeping_counter}, the Java mirror of the Rust test app's: a flow relay
 * that sleeps when idle. {@code key:delta} inputs fold into one running total
 * per key, and every input emits {@code key=total} on the {@code totals} sink,
 * which is what the sleep e2e reads after a wake.
 */
public final class SleepingCounterRelay implements Relay {

    @Override
    public String factoryId() {
        return "sleeping_counter";
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .keyBy(input -> input.split(":", 2)[0])
                .into(SleepingCounterTasklet.class)
                .sink("totals");
    }

    @Tasklet.Spec(name = "sleeping_counter_tasklet")
    public static final class SleepingCounterTasklet implements Tasklet<String, CounterTasklet.State, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CounterTasklet.State.class, CounterChange.class);
        }

        @Override
        public void run(String input, Ctx<CounterTasklet.State, String> ctx) throws TaskletException {
            String[] parts = input.split(":", 2);
            long delta = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            ctx.change(new CounterChange(delta));
            ctx.emit(parts[0] + "=" + ctx.state().value);
        }
    }
}
