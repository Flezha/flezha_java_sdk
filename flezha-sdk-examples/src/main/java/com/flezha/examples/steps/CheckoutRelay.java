package com.flezha.examples.steps;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** Reserve, charge, ship. Any failure unwinds everything already done. */
public final class CheckoutRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "checkout";
    }

    /** One pass; any failure undoes everything. KEEP_PROGRESS only matters for loops. */
    @Override
    public Run run() {
        return new Run.Steps(Each.ONCE, OnFailure.UNDO_ALL);
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        // The value between two steps is written down at that boundary, which is what lets a
        // steps relay that died after charging resume at shipping instead of starting over. Nothing
        // here declares how it is written: the runtime derives that from the channel.
        c.input(String.class, "order")
                .into(Reserve.class)
                .named("reserved")
                // Retry is the edge's: a failing charge runs up to three times, 500 ms apart,
                // before the whole thing unwinds. The other edges keep the default ladder.
                .retryMax(3)
                .retryBackoffMs(500)
                .into(Charge.class)
                .named("charged")
                .into(Ship.class)
                .output("shipped");
    }
}
