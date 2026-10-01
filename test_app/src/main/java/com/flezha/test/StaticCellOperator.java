package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code StaticCellOperator}: a static-cell
 * entry tasklet routing by the input's first character, hopping into a
 * stateless downstream tasklet that hands the bytes to the {@code test_kafka}
 * plugin.
 */
public final class StaticCellOperator implements Operator {

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

    @Tasklet.Spec(name = "static_entry_tasklet", keys = {"a", "b", "c", "d"})
    public static final class StaticEntryTasklet implements Tasklet<String, StaticEntryTasklet.State, String> {

        public record State(List<String> seen) {}

        /** One input the Cell has seen. */
        public record Seen(String text) implements Change<State> {
            @Override
            public void applyTo(State state) {
                state.seen().add(text);
            }
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of(StaticCellOperator.partition(input));
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
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "static_downstream_tasklet")
    public static final class StaticDownstreamTasklet implements Tasklet<String, Void, byte[]> {

        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
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
