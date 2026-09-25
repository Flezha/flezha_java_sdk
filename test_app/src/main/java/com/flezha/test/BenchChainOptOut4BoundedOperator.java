package com.flezha.test;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;

/** The persistence-off twin of the four-task chain ({@code --tier optout}). */
public final class BenchChainOptOut4BoundedOperator implements BoundedOperator {
    @Override
    public String factoryId() {
        return "bench_chain_optout_4";
    }

    @Override
    public boolean persistenceEnabled() {
        return false;
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 4);
    }
}
