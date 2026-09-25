package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code static_cell_bounded}: a PER_INPUT loop
 * whose one tasklet declares three static cells and routes every message to two
 * of them; each cell that runs emits {@code <cell key>:<message>}, so one
 * message yields exactly two outputs.
 */
public final class StaticCellBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "static_cell_bounded";
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
        c.input(String.class, "in").into(Keyed.class).sink("out");
    }

    /** Emits its cell key beside the message, on the two cells it names. */
    public static final class Keyed extends StatelessStep {
        @Override
        public String name() {
            return "static_cell_step";
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b", "c");
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of("a", "b");
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.emit(ctx.cellKey().orElse("") + ":" + input);
        }
    }
}
