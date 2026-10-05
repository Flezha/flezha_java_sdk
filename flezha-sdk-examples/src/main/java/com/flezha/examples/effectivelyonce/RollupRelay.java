package com.flezha.examples.effectivelyonce;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/**
 * The two settings in {@code run()} are what turn the guarantee on, and neither is optional.
 *
 * <p>{@code EffectivelyOnce} makes state, output and consumed offset become durable together in one
 * record, so a crash cannot advance the offset while losing the output. Its {@code Ack.Checkpoint}
 * is what withholds the offset until your code says the state is coherent.
 *
 * <p>Registration refuses this combination without keyed sinks, because without a key the sink has
 * no way to recognise a repeat.
 */
public final class RollupRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "rollup";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce().withAck(new Ack.Checkpoint()));
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {

        c.input(String.class, "ticks").into(RollupTasklet.class).keyed().sink("rollups");
    }
}
