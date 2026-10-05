package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code CheckpointStoreRelay}: the
 * {@link TierStoreRelay} store acknowledging its consumed offsets only
 * at an explicit {@code ctx.checkpoint()}. Emits still flush as they happen, so
 * the output sees an interval's outputs long before its offsets are acknowledged.
 */
public final class CheckpointStoreRelay implements Relay {

    @Override
    public String factoryId() {
        return "checkpoint_store";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce().withAck(new Ack.Checkpoint()));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(CheckpointStoreTasklet.class)
                .keyed()
                .output("sink");
    }
}
