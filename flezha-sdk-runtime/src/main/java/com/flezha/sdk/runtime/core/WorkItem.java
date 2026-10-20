package com.flezha.sdk.runtime.core;

import com.flezha.sdk.state.TimerFiring;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * One member of a {@link ActivationKind.Work} batch. Variant order is the
 * postcard enum discriminant.
 */
public sealed interface WorkItem {

    /** Discriminant 0 — one input payload for the target Cell. */
    record Input(
            int channel,
            @Nonnull byte[] payload,
            @Nonnull List<Map.Entry<String, String>> metadata,
            @Nonnull OptionalLong consumedOffset,
            boolean replay,
            long now,
            @Nonnull Form form,
            @Nonnull List<byte[]> values)
            implements WorkItem {}

    /**
     * Whether the consuming edge's chain still applies to an input's payload;
     * the core decides it. A {@code PREPARED} input carries this Cell's values
     * from the prepare crossing, each encoded as an edge value. Variant order
     * is the postcard enum discriminant.
     */
    enum Form {
        UNTRANSFORMED,
        TRANSFORMED,
        PREPARED
    }

    /**
     * Discriminant 1 — a validated timer firing. Its {@code now} IS
     * {@code scheduledAt} — a chained {@code timerAfter} anchors on the firing
     * that woke it, replay-stable.
     */
    record Timer(@Nonnull TimerFiring firing) implements WorkItem {}
}
