package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.Operator;

/**
 * Mirror of the Rust test app's {@code BenchCheckpointOperator}
 * ({@code mix bench.roundtrip --durability checkpoint}): the
 * {@link CheckpointStoreOperator} keyed upsert driven by a DECLARED
 * cadence instead of a {@code commit:} input.
 */
public final class BenchCheckpointOperator implements Operator {

    @Override
    public String factoryId() {
        return "bench_checkpoint";
    }

    @Override
    public Run run() {
        Ack.Checkpoint ack =
                new Ack.Checkpoint().withEvery((int) TestEnv.longVar("FLEZHA_BENCH_CHECKPOINT_EVERY", 500));
        if ("at_checkpoint".equals(System.getenv("FLEZHA_BENCH_CHECKPOINT_OUTPUTS"))) {
            ack = ack.withMaxDelayMs(TestEnv.longVar("FLEZHA_BENCH_CHECKPOINT_DELAY_MS", 50))
                    .withOutputs(Outputs.AT_CHECKPOINT);
        }
        return new Run.Flow(new Exactly.EffectivelyOnce().withAck(ack));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(BenchCheckpointTasklet.class)
                .keyed()
                .sink("sink");
    }
}
