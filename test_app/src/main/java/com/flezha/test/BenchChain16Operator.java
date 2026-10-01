package com.flezha.test;

import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.channel.ChannelBuilder;

/** {@code mix bench.steps --runtime java --tasks 16}: a sixteen-task durable chain. */
public final class BenchChain16Operator implements Operator {
    @Override
    public String factoryId() {
        return "bench_chain_16";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        BenchSteps.chain(c, 16);
    }
}
