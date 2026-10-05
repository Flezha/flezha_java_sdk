package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code TierChainRelay}: an effectively-once stream of two
 * tasklets. {@code tier_pass} counts what it forwards and passes each {@code key:value} on;
 * {@code tier_tally} counts each key's inputs and emits {@code key=count}. Until the release env is
 * set the tally fails the value {@code hold} and keeps retrying it, so a runner killed there has
 * made the pass's record durable and not the tally's.
 */
public final class TierChainRelay implements Relay {

    @Override
    public String factoryId() {
        return "tier_chain";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce());
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(TierPassTasklet.class)
                .keyBy(DynamicCellRelay::key)
                .retryMax(1_000)
                .into(TierTallyTasklet.class)
                .keyed()
                .output("sink");
    }

    /** The counter shape both tasklets share. */
    abstract static class Counting implements Tasklet<String, CounterTasklet.State, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CounterTasklet.State.class, CounterChange.class);
        }
    }

    @Tasklet.Spec(name = "tier_pass")
    public static final class TierPassTasklet extends Counting {

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
            String key = DynamicCellRelay.key(input);
            String value = key.length() < input.length() ? input.substring(key.length() + 1) : "";
            if (value.equals("hold") && !StepsE2eGate.released()) {
                throw new TaskletException("held until released");
            }
            ctx.change(new CounterChange(1));
            ctx.emitKeyed(key, key + "=" + ctx.state().value);
        }
    }
}
