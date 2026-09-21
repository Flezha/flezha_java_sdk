package com.flezha.examples.workflow;

import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import javax.annotation.Nonnull;

/** Holds the stock, and knows how to release it again. */
public final class Reserve extends Step {

    @Override
    @Nonnull
    public String name() {
        return "reserve";
    }

    @Override
    public void run(@Nonnull String order, @Nonnull StateHandle<Handle, Reserved> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        // ctx.stableId(), not UUID.randomUUID(): a re-run derives the same id, so the
        // warehouse sees a retry rather than a second reservation.
        String reservationId = ctx.stableId().toString();
        state.apply(new Reserved(reservationId));
        ctx.emit(order + "|reservation=" + reservationId);
    }

    @Override
    public boolean compensateOverridden() {
        return true;
    }

    @Override
    public void compensate(
            @Nonnull List<String> completedOutput,
            @Nonnull StateHandle<Handle, Reserved> state,
            @Nonnull TaskletCtx ctx) {
        // completedOutput can be empty after a crash-resumed rollback. The handle comes
        // from state, which is always restored.
        System.out.println("releasing reservation " + state.get().reservationId);
    }
}
