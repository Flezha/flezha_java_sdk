package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;

/** The persistence-off twin of the four-task chain ({@code --tier optout}). */
public final class BenchChainOptOut4WorkflowOperative implements WorkflowOperative {
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
