package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Java equivalent of the Rust SDK's {@code StatefulCounterUnboundedOperator}:
 * {@link CounterEntryTasklet} parses the decimal input and the {@code ticks}
 * channel carries the delta to {@link CounterTasklet}, which records it as a
 * {@link CounterChange}.
 */
public final class StatefulCounterUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "stateful_counter";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(CounterEntryTasklet.class).named("ticks").into(CounterTasklet.class);
    }
}
