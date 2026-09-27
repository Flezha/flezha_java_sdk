package com.flezha.examples.bounded;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/** Takes the money, and knows how to refund it. */
@Tasklet.Spec(name = "charge")
public final class Charge extends Step {

    @Override
    public void run(String reserved, Ctx<Handle, String> ctx) throws TaskletException {
        ctx.emit(reserved + "|charged");
    }

    @Override
    public void compensate(List<String> completedOutput, Ctx<Handle, String> ctx) {
        System.out.println("refunding order");
    }
}
