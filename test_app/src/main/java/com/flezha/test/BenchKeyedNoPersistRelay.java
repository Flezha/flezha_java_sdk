package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code BenchKeyedNoPersistRelay}:
 * {@link BenchKeyedRelay} with local state persistence off, isolating
 * the per-Cell WAL cost from the keyed upsert itself.
 */
public final class BenchKeyedNoPersistRelay implements Relay {

    @Override
    public String factoryId() {
        return "bench_keyed_nopersist";
    }

    @Override
    public Durable durable() {
        return new Durable.Memory();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(TierStoreTasklet.class)
                .keyed()
                .output("sink");
    }
}
