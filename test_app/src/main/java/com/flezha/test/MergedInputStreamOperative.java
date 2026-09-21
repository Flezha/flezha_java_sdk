package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;
import java.util.function.Function;

/**
 * Mirror of the Rust test app's {@code MergedInputOperative}: two entry
 * channels merged into one tasklet. Each source's mapping tags the value with
 * the channel it came from, the merge keys on the symbol the mapping read, and
 * the emitted line is {@code <source>:<payload>}.
 */
public final class MergedInputStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "merged_input";
    }

    @Override
    public void channels(ChannelBuilder c) {
        var ticks = c.input(String.class, "ticks");
        var quotes = c.input(String.class, "quotes");
        c.merge(MergedInput.class)
                .from(ticks, tagged("ticks"))
                .from(quotes, tagged("quotes"))
                .keyBy(MergedInput::symbol)
                .into(MergeLabelTasklet.class)
                .keyed()
                .sink("merged");
    }

    public record MergedInput(String source, String symbol, String payload) {}

    private static Function<String, MergedInput> tagged(String source) {
        return payload -> new MergedInput(source, DynamicCellStreamOperative.key(payload), payload);
    }

    public static final class MergeLabelTasklet
            implements Tasklet<MergedInput, MergeLabelTasklet.State, String, NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "merge_label_tasklet";
        }

        @Override
        public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new State();
        }

        @Override
        public void run(MergedInput input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input.source() + ":" + input.payload());
        }

        public record State() {}
    }
}
