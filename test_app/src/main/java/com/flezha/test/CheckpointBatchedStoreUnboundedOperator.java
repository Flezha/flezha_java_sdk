package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust test app's {@code CheckpointBatchedOperator}: the same
 * tasklet as {@link CheckpointStoreUnboundedOperator} declaring
 * {@code outputsAtCheckpoint}, so the interval's outputs ship inside its
 * checkpoint record. No {@code checkpointEvery} — the test drives the close
 * itself, with {@code commit:} for the batch case and the delay alone for the
 * quiet-Cell case.
 */
public final class CheckpointBatchedStoreUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "checkpoint_batched_store";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();
        c.checkpointAck();
        c.outputsAtCheckpoint(1_500);
        c.input(String.class, "input")
                .into(CheckpointStoreTasklet.class)
                .keyed()
                .sink("sink");
    }
}
