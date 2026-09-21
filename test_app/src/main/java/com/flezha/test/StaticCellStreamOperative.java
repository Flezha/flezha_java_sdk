package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code StaticCellOperative}: a static-cell
 * entry tasklet routing by the input's first character, hopping into a
 * stateless downstream tasklet that hands the bytes to the {@code test_kafka}
 * plugin.
 */
public final class StaticCellStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "static_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(StaticEntryTasklet.class)
                .named("hop")
                .into(StaticDownstreamTasklet.class)
                .sink("kafka");
    }

    public static final class StaticEntryTasklet
            implements Tasklet<String, StaticEntryTasklet.State, String, StoreInputChange, WallClock, Void> {

        public record State(List<String> seen) {}

        @Override
        public String name() {
            return "static_entry_tasklet";
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b", "c", "d");
        }

        @Override
        public boolean cellsForOverridden() {
            return true;
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of(StaticCellStreamOperative.partition(input));
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
            ctx.emit(input);
        }
    }

    public static final class StaticDownstreamTasklet
            implements Tasklet<String, Stateless, byte[], NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "static_downstream_tasklet";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The static cell key an input routes to: its first character, {@code d} otherwise. */
    static String partition(String input) {
        char first = input.isEmpty() ? ' ' : input.charAt(0);
        return switch (first) {
            case 'a' -> "a";
            case 'b' -> "b";
            case 'c' -> "c";
            default -> "d";
        };
    }
}
