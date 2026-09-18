package com.flezha.examples.spawn;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.WorkflowOperative;
import javax.annotation.Nonnull;

/** The worker: one per subject, torn down when its single pass completes. */
public final class TriageOperative implements WorkflowOperative {

    @Override
    @Nonnull
    public String factoryId() {
        return "triage";
    }

    /** The spawn payload, the alert that asked for this worker, seeds the one pass. */
    @Override
    public void procedures(@Nonnull ChannelBuilder c) {
        c.input(String.class, "alert").into(TriageProcedure.class).sink("triaged");
    }
}
