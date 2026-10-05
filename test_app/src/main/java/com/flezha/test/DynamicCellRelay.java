package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust runner's {@code DynamicCellRelay}: the Cell key is
 * declared on the WIRING, so one input per fresh key lazily creates a Cell
 * and the literal {@code "drop"} is discarded by a {@code filter} rather than by
 * a keyless return.
 */
public final class DynamicCellRelay implements Relay {

    @Override
    public String factoryId() {
        return "dynamic_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .filter(input -> !input.equals("drop"))
                .keyBy(DynamicCellRelay::key)
                .into(DynamicCellTasklet.class)
                .output("kafka");
    }

    /** Key = text before the first {@code :}; {@code partition_pass} shares it. */
    static String key(String input) {
        int at = input.indexOf(':');
        return at < 0 ? input : input.substring(0, at);
    }
}
