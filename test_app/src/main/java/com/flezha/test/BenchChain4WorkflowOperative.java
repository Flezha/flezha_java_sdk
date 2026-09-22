package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.workflow --runtime java --tasks 4}: a four-task durable chain. */
public final class BenchChain4WorkflowOperative implements WorkflowOperative {
    @Override
    public String factoryId() {
        return "bench_chain_4";
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 4);
    }
}
