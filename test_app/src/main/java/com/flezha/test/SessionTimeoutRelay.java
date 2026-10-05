package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/** Mirror of the Rust runner's {@code SessionTimeoutRelay}. */
public final class SessionTimeoutRelay implements Relay {

    @Override
    public String factoryId() {
        return "session_timeout";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(SessionTimeoutTasklet.class)
                .sink("kafka");
    }
}
