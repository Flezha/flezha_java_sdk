package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import java.util.Optional;

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
    abstract static class Counting
            implements Tasklet<String, CounterTasklet.State, String, CounterChange, WallClock, Void> {

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
    }

    public static final class TierRelayTasklet extends Counting {

        @Override
        public String name() {
            return "tier_relay";
        }

        @Override
        public void run(String input, StateHandle<CounterTasklet.State, CounterChange> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new CounterChange(1));
            ctx.emit(input);
        }
    }

    public static final class TierTallyTasklet extends Counting {

        @Override
        public String name() {
            return "tier_tally";
        }

        @Override
        public void run(String input, StateHandle<CounterTasklet.State, CounterChange> state, TaskletCtx ctx)
                throws TaskletException {
            String key = DynamicCellUnboundedOperator.key(input);
            String value = key.length() < input.length() ? input.substring(key.length() + 1) : "";
            if (value.equals("hold") && !BoundedE2eGate.released()) {
                throw new TaskletException("held until released");
            }
            state.apply(new CounterChange(1));
            ctx.emitKeyed(key, key + "=" + state.get().value);
        }
    }
}
