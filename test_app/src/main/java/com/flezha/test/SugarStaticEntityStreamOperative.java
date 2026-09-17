package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Multiprocedure reference operative authored with explicit topology, wiring the generated
 * {@code SugarEntryProcedure} -> {@code SugarDownstreamProcedure} -> plugin. {@code Main} registers
 * it with {@code registerFactory}, as it registers every operative.
 */
public class SugarStaticEntityStreamOperative implements com.flezha.sdk.operative.StreamOperative {

    @Override
    public String factoryId() {
        return "sugar_static_entity";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(SugarEntryProcedure.class)
                .into(SugarDownstreamProcedure.class)
                .sink("test_kafka");
    }
}
