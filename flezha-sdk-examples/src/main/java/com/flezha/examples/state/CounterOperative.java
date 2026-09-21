package com.flezha.examples.state;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import javax.annotation.Nonnull;

/**
 * Identical in shape to the echo example's operative, which is the point: making a tasklet
 * durable altered the tasklet and left the declared graph unchanged.
 *
 * <p>No key is declared, so every message is routed to a single cell and there is one count for
 * the whole operative. The {@code keyed} example covers partitioning into one cell per key.
 */
public final class CounterOperative implements StreamOperative {

    @Override
    @Nonnull
    public String factoryId() {
        return "counter";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "in").into(CountTasklet.class).sink("out");
    }
}
