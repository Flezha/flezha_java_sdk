package com.flezha.examples.steps;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** The last step. Nothing after it, so it writes no {@code compensate}. */
@Tasklet.Spec(name = "ship")
public final class Ship extends Step {

    @Override
    public void run(String charged, Ctx<Handle, String> ctx) throws TaskletException {
        ctx.emit(charged + "|shipped");
    }
}
