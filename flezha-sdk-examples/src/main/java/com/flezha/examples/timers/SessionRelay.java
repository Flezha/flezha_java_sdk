package com.flezha.examples.timers;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** One cell per user, so every session owns its own idle deadline. */
public final class SessionRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "session_window";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "clicks")
                .keyBy(click -> click.split(":")[0])
                .into(SessionWindow.class)
                .sink("closed_sessions");
    }
}
