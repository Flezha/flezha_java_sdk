package com.flezha.examples.agent;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import javax.annotation.Nonnull;

/** Wakes on each message. */
public final class ChatOperator implements BoundedOperator {

    @Override
    @Nonnull
    public String factoryId() {
        return "chat_agent";
    }

    /** One iteration per inbound message. */
    @Override
    @Nonnull
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "message").into(ReplyTasklet.class).sink("reply");
    }
}
