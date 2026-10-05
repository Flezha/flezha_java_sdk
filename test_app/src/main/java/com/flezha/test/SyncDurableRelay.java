package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code sync_durable_steps}: the
 * {@link CommitBlockRelay} shape declaring SYNCHRONOUS remote
 * durability. The controller must have task A's journal barrier on the
 * durability backend before it acks the commit — which is what lets the
 * kill-the-disk drill destroy the whole state directory and still resume with
 * task A committed rather than re-run.
 */
public final class SyncDurableRelay implements Relay {

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
                .into(CommitBlockRelay.CommitA.class)
                .named("mid")
                .into(CommitBlockRelay.BlockB.class);
    }
}
