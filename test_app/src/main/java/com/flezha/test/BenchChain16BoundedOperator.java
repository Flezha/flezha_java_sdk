package com.flezha.test;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.bounded --runtime java --tasks 16}: a sixteen-task durable chain. */
public final class BenchChain16BoundedOperator implements BoundedOperator {
    @Override
    public String factoryId() {
        return "bench_chain_16";
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 16);
    }
}
