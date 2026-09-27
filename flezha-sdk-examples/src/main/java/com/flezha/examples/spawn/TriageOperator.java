package com.flezha.examples.spawn;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import javax.annotation.Nonnull;

/** The worker: one per subject, torn down when its single pass completes. */
public final class TriageOperator implements BoundedOperator {

    @Override
    @Nonnull
    public String factoryId() {
        return "triage";
    }

    /** The spawn payload, the alert that asked for this worker, seeds the one pass. */
    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "alert").into(Triage.class).sink("triaged");
    }
}
