package com.flezha.examples.workflow;

import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import javax.annotation.Nonnull;

/** Takes the money, and knows how to refund it. */
public final class Charge extends Step {

    @Override
    @Nonnull
    public String name() {
        return "charge";
    }

    @Override
    public void run(@Nonnull String reserved, @Nonnull StateHandle<Handle, Reserved> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        ctx.emit(reserved + "|charged");
    }

    @Override
    public void compensate(
            @Nonnull List<String> completedOutput,
            @Nonnull StateHandle<Handle, Reserved> state,
            @Nonnull TaskletCtx ctx) {
        System.out.println("refunding order");
    }
}
