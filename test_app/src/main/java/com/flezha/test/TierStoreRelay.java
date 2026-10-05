package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code TierStoreRelay}: the effectively-once
 * fixture. A 300 ms backoff base keeps the min-watermark freeze observable in the
 * dead-letter e2e, and the entry channel is declared {@code DROP_NOTHING} because a
 * durable-input-log relay that shed at its mailbox would hold the
 * min-watermark on the shed offset forever.
 */
public final class TierStoreRelay implements Relay {

    @Override
    public String factoryId() {
        return "tier_store";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce());
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .retryBackoffMs(300)
                .into(TierStoreTasklet.class)
                .keyed()
                .sink("sink");
    }
}
