package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

/**
 * Mirror of the Rust SDK's {@code CompareFilterOperator}: filters by
 * comparing the input against a stored value held in the Tasklet's State.
 */
public final class CompareFilterOperator implements Operator {

    @Override
    public String factoryId() {
        return "compare_filter";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(CompareFilterTasklet.class).sink("sink");
    }
}
