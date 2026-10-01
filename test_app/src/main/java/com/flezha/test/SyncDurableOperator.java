package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

/**
 * Mirror of the Rust test app's {@code sync_durable_steps}: the
 * {@link CommitBlockOperator} shape declaring SYNCHRONOUS remote
 * durability. The controller must have task A's journal barrier on the
 * durability backend before it acks the commit — which is what lets the
 * kill-the-disk drill destroy the whole state directory and still resume with
 * task A committed rather than re-run.
 */
public final class SyncDurableOperator implements Operator {

    @Override
    public String factoryId() {
        return "sync_durable_steps";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return Durable.remote();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(CommitBlockOperator.CommitA.class)
                .named("mid")
                .into(CommitBlockOperator.BlockB.class);
    }
}
