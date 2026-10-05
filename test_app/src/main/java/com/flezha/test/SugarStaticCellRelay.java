package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Multiprocedure reference relay authored with explicit topology, wiring
 * {@link SugarEntry} -> {@link SugarDownstream} -> plugin. {@code Main} registers
 * it with {@code registerFactory}, as it registers every relay.
 */
public class SugarStaticCellRelay implements com.flezha.sdk.relay.Relay {

    @Override
    public String factoryId() {
        return "sugar_static_cell";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(SugarEntry.class)
                .into(SugarDownstream.class)
                .output("test_kafka");
    }
}
