package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;

/**
 * Mirror of the Rust test app's {@code sync_durable_bounded}: the
 * {@link CommitBlockBoundedOperator} shape declaring SYNCHRONOUS remote
 * durability. The controller must have task A's journal barrier on the
 * durability backend before it acks the commit — which is what lets the
 * kill-the-disk drill destroy the whole state directory and still resume with
 * task A committed rather than re-run.
 */
public final class SyncDurableBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "sync_durable_bounded";
    }

    @Override
    public boolean syncDurability() {
        return true;
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(CommitBlockBoundedOperator.CommitA.class)
                .named("mid")
                .into(CommitBlockBoundedOperator.BlockB.class);
    }
}
