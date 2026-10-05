package com.flezha.examples.agent;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** Wakes on each message. */
public final class ChatRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "chat_agent";
    }

    /** One iteration per inbound message. */
    @Override
    public Run run() {
        return Run.steps(Each.PER_INPUT);
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "message").into(Reply.class).output("reply");
    }
}
