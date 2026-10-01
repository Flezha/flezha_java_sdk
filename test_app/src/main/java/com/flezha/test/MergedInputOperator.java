package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.function.Function;

/**
 * Mirror of the Rust test app's {@code MergedInputOperator}: two entry
 * channels merged into one tasklet. Each source's mapping tags the value with
 * the channel it came from, the merge keys on the symbol the mapping read, and
 * the emitted line is {@code <source>:<payload>}.
 */
public final class MergedInputOperator implements Operator {

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
        return payload -> new MergedInput(source, DynamicCellOperator.key(payload), payload);
    }

    @Tasklet.Spec(name = "merge_label_tasklet")
    public static final class MergeLabelTasklet implements Tasklet<MergedInput, Void, String> {

        @Override
        public void run(MergedInput input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.source() + ":" + input.payload());
        }
    }
}
