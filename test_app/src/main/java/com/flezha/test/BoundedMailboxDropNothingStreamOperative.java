package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operative.StreamOperative;

/** Mirror of the Rust test app's {@code BoundedMailboxDropNothingOperative}. */
public final class BoundedMailboxDropNothingStreamOperative implements StreamOperative {

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

    public static final class DropNothingTestTasklet extends BoundedMailboxTasklet {
        @Override
        public String name() {
            return "drop_nothing_test_tasklet";
        }
    }
}
