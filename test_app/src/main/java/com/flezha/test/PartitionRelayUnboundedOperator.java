package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code PartitionRelayOperator}: the
 * partitioned-operators e2e's upstream — keyed on the wiring (so it can be
 * partitioned at all) AND emitting keyed (so a partitioned forward target can
 * resolve the owning member).
 */
public final class PartitionRelayUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "partition_relay";
    }

    @Override
    public void channels(ChannelBuilder c) {
        // The plugin channel is named `input` because a forwarded frame keeps
        // its producing channel's name in metadata, and that is what selects
        // the receiving operator's channel — the downstream fixture's entry
        // channel. The entry channel here is `in` so the two names cannot
        // collide inside this class.
        c.input(String.class, "in")
                .keyBy(DynamicCellUnboundedOperator::key)
                .into(PartitionRelayTasklet.class)
                .keyed()
                .sink("input");
    }

    public static final class PartitionRelayTasklet
            implements Tasklet<String, PartitionRelayTasklet.State, String, StoreInputChange, WallClock, Void> {

        public record State(List<String> seen) {}

        @Override
        public String name() {
            return "partition_relay_tasklet";
        }

        @Override
        public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new State(new ArrayList<>());
        }

        @Override
        public void applyChange(State state, StoreInputChange change) {
            state.seen().add(change.text());
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, StoreInputChange.class, ArrayList.class);
        }

        @Override
        public void run(String input, StateHandle<State, StoreInputChange> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new StoreInputChange(input));
            ctx.emitKeyed(DynamicCellUnboundedOperator.key(input), input);
        }
    }
}
