package com.flezha.examples.spawn;

import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Triages the alert the worker was spawned for. */
public final class TriageProcedure implements Procedure<String, Void, String, NoChange, WallClock, Void> {

    @Override
    @Nonnull
    public String name() {
        return "triage";
    }

    @Override
    public Void initState(@Nonnull Optional<String> entityKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return null;
    }

    @Override
    public void run(@Nonnull String alert, @Nonnull StateHandle<Void, NoChange> state, @Nonnull ProcedureCtx ctx)
            throws ProcedureException {
        ctx.emit("triaged:" + alert);
    }
}
