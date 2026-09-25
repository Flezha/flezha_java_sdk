package com.flezha.test;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.bounded --runtime java --tasks 1}: a one-task durable chain. */
public final class BenchChain1BoundedOperator implements BoundedOperator {
    @Override
    public String factoryId() {
        return "bench_chain_1";
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 1);
    }
}
