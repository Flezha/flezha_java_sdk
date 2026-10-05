package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code TransformChainRelay}: every value
 * transform on one edge. {@code drop} is filtered out, a comma list fans out
 * into one value per element, each is upper-cased and keyed on its prefix.
 */
public final class TransformChainRelay implements Relay {

    @Override
    public String factoryId() {
        return "transform_chain";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "trades")
                .filter(t -> !t.equals("drop"))
                .flatMap(String.class, t -> List.of(t.split(",")))
                .map(String.class, String::toUpperCase)
                .keyBy(DynamicCellRelay::key)
                .into(TransformLabelTasklet.class)
                .keyed()
                .sink("transformed");
    }

    @Tasklet.Spec(name = "transform_label_tasklet")
    public static final class TransformLabelTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }
}
