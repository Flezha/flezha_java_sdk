package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust test app's {@code BenchKeyedNoPersistOperator}:
 * {@link BenchKeyedUnboundedOperator} with local state persistence off, isolating
 * the per-Cell WAL cost from the keyed upsert itself.
 */
public final class BenchKeyedNoPersistUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "bench_keyed_nopersist";
    }

    @Override
    public boolean persistenceEnabled() {
        return false;
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(TierStoreTasklet.class)
                .keyed()
                .sink("sink");
    }
}
