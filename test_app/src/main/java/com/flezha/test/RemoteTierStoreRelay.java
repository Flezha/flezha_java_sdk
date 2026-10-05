package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code RemoteTierStoreRelay}: the
 * {@link TierStoreRelay} store kept {@code durable: remote}, so an input is
 * acknowledged only once the durability backend holds the records it produced.
 */
public final class RemoteTierStoreRelay implements Relay {

    private final TierStoreRelay store = new TierStoreRelay();

    @Override
    public String factoryId() {
        return "remote_tier_store";
    }

    @Override
    public Run run() {
        return store.run();
    }

    @Override
    public Durable durable() {
        return Durable.remote();
    }

    @Override
    public void channels(ChannelBuilder c) {
        store.channels(c);
    }
}
