package com.flezha.examples.bounded;

import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import javax.annotation.Nonnull;

/** The last step. Nothing after it, so it needs no undo. */
public final class Ship extends Step {

    @Override
    @Nonnull
    public String name() {
        return "ship";
    }

    @Override
    public void run(@Nonnull String charged, @Nonnull StateHandle<Handle, Reserved> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        ctx.emit(charged + "|shipped");
    }
}
