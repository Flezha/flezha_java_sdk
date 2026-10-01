package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Multiprocedure reference operator authored with explicit topology, wiring
 * {@link SugarEntry} -> {@link SugarDownstream} -> plugin. {@code Main} registers
 * it with {@code registerFactory}, as it registers every operator.
 */
public class SugarStaticCellOperator implements com.flezha.sdk.operator.Operator {

    @Override
    public String factoryId() {
        return "sugar_static_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(SugarEntry.class)
                .into(SugarDownstream.class)
                .sink("test_kafka");
    }
}
