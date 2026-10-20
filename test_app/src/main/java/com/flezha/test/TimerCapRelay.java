package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/** Mirror of the Rust runner's {@code TimerCapRelay}. */
public final class TimerCapRelay implements Relay {

    @Override
    public String factoryId() {
        return "timer_cap";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(TimerCapTasklet.class)
                .output("kafka");
    }
}
