package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Tasklet;

/** Mirror of the Rust test app's {@code BoundedMailboxDropNothingRelay}. */
public final class BoundedMailboxDropNothingRelay implements Relay {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_nothing";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_nothing_input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(DropNothingTestTasklet.class)
                .output("drop_nothing_echo");
    }

    @Tasklet.Spec(name = "drop_nothing_test_tasklet", capacity = BoundedMailboxTasklet.CAPACITY)
    public static final class DropNothingTestTasklet extends BoundedMailboxTasklet {}
}
