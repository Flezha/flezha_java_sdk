package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust test app's {@code CheckpointStoreOperator}: the
 * {@link TierStoreUnboundedOperator} store acknowledging its consumed offsets only
 * at an explicit {@code ctx.checkpoint()}. Emits still flush as they happen, so
 * the sink sees an interval's outputs long before its offsets are acknowledged.
 */
public final class CheckpointStoreUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "checkpoint_store";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();
        c.checkpointAck();
        c.input(String.class, "input")
                .into(CheckpointStoreTasklet.class)
                .keyed()
                .sink("sink");
    }
}
