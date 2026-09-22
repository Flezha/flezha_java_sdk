package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.workflow --runtime java --tasks 16}: a sixteen-task durable chain. */
public final class BenchChain16WorkflowOperative implements WorkflowOperative {
    @Override
    public String factoryId() {
        return "bench_chain_16";
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 16);
    }
}
