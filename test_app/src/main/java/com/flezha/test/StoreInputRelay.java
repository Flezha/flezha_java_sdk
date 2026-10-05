package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust SDK's {@code StoreInputRelay}: appends each input
 * to the Tasklet's State and passes it on to the {@code store_input_sink}
 * plugin.
 */
public final class StoreInputRelay implements Relay {

    @Override
    public String factoryId() {
        return "store_input";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(StoreInputTasklet.class).sink("sink");
    }
}
