package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Tasklet;

/** Mirror of the Rust test app's {@code BoundedMailboxDropOldestOperator}. */
public final class BoundedMailboxDropOldestUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_oldest";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_oldest_input")
                .onFull(OverflowPolicy.DROP_OLDEST)
                .into(DropOldestTestTasklet.class)
                .sink("drop_oldest_echo");
    }

    @Tasklet.Spec(name = "drop_oldest_test_tasklet", capacity = BoundedMailboxTasklet.CAPACITY)
    public static final class DropOldestTestTasklet extends BoundedMailboxTasklet {}
}
