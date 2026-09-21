package com.flezha.test;

import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.workflow --runtime java --tasks 1}: a one-task durable chain. */
public final class BenchChain1WorkflowOperative implements WorkflowOperative {
    @Override
    public String factoryId() {
        return "bench_chain_1";
    }

    @Override
    public void tasklets(ChannelBuilder c) {
        BenchSteps.chain(c, 1);
    }
}
