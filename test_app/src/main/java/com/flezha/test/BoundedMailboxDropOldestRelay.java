package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Tasklet;

/** Mirror of the Rust test app's {@code BoundedMailboxDropOldestRelay}. */
public final class BoundedMailboxDropOldestRelay implements Relay {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_oldest";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_oldest_input")
                .onFull(OverflowPolicy.DROP_OLDEST)
                .into(DropOldestTestTasklet.class)
                .output("drop_oldest_echo");
    }

    @Tasklet.Spec(name = "drop_oldest_test_tasklet", mailboxCapacity = BoundedMailboxTasklet.CAPACITY)
    public static final class DropOldestTestTasklet extends BoundedMailboxTasklet {}
}
