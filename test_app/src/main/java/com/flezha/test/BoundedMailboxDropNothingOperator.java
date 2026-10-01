package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Tasklet;

/** Mirror of the Rust test app's {@code BoundedMailboxDropNothingOperator}. */
public final class BoundedMailboxDropNothingOperator implements Operator {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_nothing";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_nothing_input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(DropNothingTestTasklet.class)
                .sink("drop_nothing_echo");
    }

    @Tasklet.Spec(name = "drop_nothing_test_tasklet", capacity = BoundedMailboxTasklet.CAPACITY)
    public static final class DropNothingTestTasklet extends BoundedMailboxTasklet {}
}
