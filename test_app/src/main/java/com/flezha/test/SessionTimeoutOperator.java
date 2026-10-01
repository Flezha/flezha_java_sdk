package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

/** Mirror of the Rust runner's {@code SessionTimeoutOperator}. */
public final class SessionTimeoutOperator implements Operator {

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
