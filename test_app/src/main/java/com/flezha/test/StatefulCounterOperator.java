package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

/**
 * Java equivalent of the Rust SDK's {@code StatefulCounterOperator}:
 * {@link CounterEntryTasklet} parses the decimal input and the {@code ticks}
 * channel carries the delta to {@link CounterTasklet}, which records it as a
 * {@link CounterChange}.
 */
public final class StatefulCounterOperator implements Operator {

    @Override
    public String factoryId() {
        return "stateful_counter";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(CounterEntryTasklet.class).named("ticks").into(CounterTasklet.class);
    }
}
