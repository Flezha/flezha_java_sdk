package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code PartitionRelayOperator}: the
 * partitioned-operators e2e's upstream — keyed on the wiring (so it can be
 * partitioned at all) AND emitting keyed (so a partitioned forward target can
 * resolve the owning member).
 */
public final class PartitionRelayOperator implements Operator {

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
                .keyBy(DynamicCellOperator::key)
                .into(PartitionRelayTasklet.class)
                .keyed()
                .sink("input");
    }

    @Tasklet.Spec(name = "partition_relay_tasklet")
    public static final class PartitionRelayTasklet implements Tasklet<String, PartitionRelayTasklet.State, String> {

        public record State(List<String> seen) {}

        /** One input the Cell has relayed. */
        public record Seen(String text) implements Change<State> {
            @Override
            public void applyTo(State state) {
                state.seen().add(text);
            }
        }

        @Override
        public State initState(Optional<String> cellKey) {
            return new State(new ArrayList<>());
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, Seen.class, ArrayList.class);
        }

        @Override
        public void run(String input, Ctx<State, String> ctx) throws TaskletException {
            ctx.change(new Seen(input));
            ctx.emitKeyed(DynamicCellOperator.key(input), input);
        }
    }
}
