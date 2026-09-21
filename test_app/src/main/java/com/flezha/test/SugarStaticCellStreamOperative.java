package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Multiprocedure reference operative authored with explicit topology, wiring the generated
 * {@code SugarEntryTasklet} -> {@code SugarDownstreamTasklet} -> plugin. {@code Main} registers
 * it with {@code registerFactory}, as it registers every operative.
 */
public class SugarStaticCellStreamOperative implements com.flezha.sdk.operative.StreamOperative {

    @Override
    public String factoryId() {
        return "sugar_static_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(SugarEntryTasklet.class)
                .into(SugarDownstreamTasklet.class)
                .sink("test_kafka");
    }
}
