package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;

/** Mirror of the Rust test app's {@code BoundedMailboxDropNewestOperator}. */
public final class BoundedMailboxDropNewestUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_newest";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_newest_input")
                .onFull(OverflowPolicy.DROP_NEWEST)
                .into(DropNewestTestTasklet.class)
                .sink("drop_newest_echo");
    }

    public static final class DropNewestTestTasklet extends BoundedMailboxTasklet {
        @Override
        public String name() {
            return "drop_newest_test_tasklet";
        }
    }
}
