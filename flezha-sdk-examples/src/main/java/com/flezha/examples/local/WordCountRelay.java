package com.flezha.examples.local;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** Splits lines into words, then counts each word in its own cell. */
public final class WordCountRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "word_count";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "lines")
                .into(Split.class)
                .keyBy(word -> word)
                .into(Tally.class)
                .keyed()
                .sink("totals");
    }
}
