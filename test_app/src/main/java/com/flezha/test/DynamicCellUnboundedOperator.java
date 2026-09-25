package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust runner's {@code DynamicCellOperator}: the Cell key is
 * declared on the WIRING, so one input per fresh key lazily creates a Cell
 * and the literal {@code "drop"} is discarded by a {@code filter} rather than by
 * a keyless return.
 */
public final class DynamicCellUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "dynamic_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .filter(input -> !input.equals("drop"))
                .keyBy(DynamicCellUnboundedOperator::key)
                .into(DynamicCellTasklet.class)
                .sink("kafka");
    }

    /** Key = text before the first {@code :}; {@code partition_relay} shares it. */
    static String key(String input) {
        int at = input.indexOf(':');
        return at < 0 ? input : input.substring(0, at);
    }
}
