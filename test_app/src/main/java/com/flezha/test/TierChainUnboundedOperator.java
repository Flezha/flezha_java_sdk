package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code TierChainOperator}: an effectively-once stream of two
 * tasklets. {@code tier_relay} counts what it relays and passes each {@code key:value} on;
 * {@code tier_tally} counts each key's inputs and emits {@code key=count}. Until the release env is
 * set the tally fails the value {@code hold} and keeps retrying it, so a runner killed there has
 * made the relay's record durable and not the tally's.
 */
public final class TierChainUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "tier_chain";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(TierRelayTasklet.class)
                .keyBy(DynamicCellUnboundedOperator::key)
                .retryMax(1_000)
                .into(TierTallyTasklet.class)
                .keyed()
                .sink("sink");
    }

    /** The counter shape both tasklets share. */
    abstract static class Counting implements Tasklet<String, CounterTasklet.State, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CounterTasklet.State.class, CounterChange.class);
        }
    }

    @Tasklet.Spec(name = "tier_relay")
    public static final class TierRelayTasklet extends Counting {

        @Override
        public void run(String input, Ctx<CounterTasklet.State, String> ctx) throws TaskletException {
            ctx.change(new CounterChange(1));
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "tier_tally")
    public static final class TierTallyTasklet extends Counting {

        @Override
        public void run(String input, Ctx<CounterTasklet.State, String> ctx) throws TaskletException {
            String key = DynamicCellUnboundedOperator.key(input);
            String value = key.length() < input.length() ? input.substring(key.length() + 1) : "";
            if (value.equals("hold") && !BoundedE2eGate.released()) {
                throw new TaskletException("held until released");
            }
            ctx.change(new CounterChange(1));
            ctx.emitKeyed(key, key + "=" + ctx.state().value);
        }
    }
}
