package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * The {@code coalescing_counter} fixture: {@link TickChange} records every tick.
 * Coalescing is the Rust SDK's; this relay keeps the fixture's topology.
 */
public final class CoalescingCounterRelay implements Relay {

    @Override
    public String factoryId() {
        return "coalescing_counter";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(TickEntryTasklet.class).named("ticks").into(TickTasklet.class);
    }
}
