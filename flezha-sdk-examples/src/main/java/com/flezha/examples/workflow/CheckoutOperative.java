package com.flezha.examples.workflow;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.RollbackMode;
import com.flezha.sdk.operative.WorkflowOperative;
import javax.annotation.Nonnull;

/** Reserve, charge, ship. Any failure unwinds everything already done. */
public final class CheckoutOperative implements WorkflowOperative {

    @Override
    @Nonnull
    public String factoryId() {
        return "checkout";
    }

    /** Undo everything on failure. KEEP_PROGRESS only matters for loops. */
    @Override
    @Nonnull
    public RollbackMode rollbackMode() {
        return RollbackMode.ALL_OR_NOTHING;
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        // The value between two steps is written down at that boundary, which is what lets a
        // workflow that died after charging resume at shipping instead of starting over. Nothing
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
                .sink("shipped");
    }
}
