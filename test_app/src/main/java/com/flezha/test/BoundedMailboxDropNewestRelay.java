package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Tasklet;

/** Mirror of the Rust test app's {@code BoundedMailboxDropNewestRelay}. */
public final class BoundedMailboxDropNewestRelay implements Relay {

    @Override
    public String factoryId() {
        return "bounded_mailbox_drop_newest";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "drop_newest_input")
                .onFull(OverflowPolicy.DROP_NEWEST)
                .into(DropNewestTestTasklet.class)
                .output("drop_newest_echo");
    }

    @Tasklet.Spec(name = "drop_newest_test_tasklet", mailboxCapacity = BoundedMailboxTasklet.CAPACITY)
    public static final class DropNewestTestTasklet extends BoundedMailboxTasklet {}
}
