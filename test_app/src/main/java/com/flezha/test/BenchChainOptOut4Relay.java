package com.flezha.test;

import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.channel.ChannelBuilder;

/** The persistence-off twin of the four-task chain ({@code --tier optout}). */
public final class BenchChainOptOut4Relay implements Relay {
    @Override
    public String factoryId() {
        return "bench_chain_optout_4";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return new Durable.Memory();
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 4);
    }
}
