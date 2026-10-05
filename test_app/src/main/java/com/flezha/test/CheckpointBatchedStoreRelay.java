package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code CheckpointBatchedRelay}: the same
 * tasklet as {@link CheckpointStoreRelay} holding its outputs
 * ({@code Outputs.AT_CHECKPOINT}), so the interval's outputs ship inside its
 * checkpoint record. No {@code every} — the test drives the close
 * itself, with {@code commit:} for the batch case and the delay alone for the
 * quiet-Cell case.
 */
public final class CheckpointBatchedStoreRelay implements Relay {

    @Override
    public String factoryId() {
        return "checkpoint_batched_store";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce()
                .withAck(new Ack.Checkpoint().withMaxDelayMs(1_500).withOutputs(Outputs.AT_CHECKPOINT)));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(CheckpointStoreTasklet.class)
                .keyed()
                .sink("sink");
    }
}
