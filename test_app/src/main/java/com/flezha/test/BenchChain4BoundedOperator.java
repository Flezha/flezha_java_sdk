package com.flezha.test;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.bounded --runtime java --tasks 4}: a four-task durable chain. */
public final class BenchChain4BoundedOperator implements BoundedOperator {
    @Override
    public String factoryId() {
        return "bench_chain_4";
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 4);
    }
}
