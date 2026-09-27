package com.flezha.examples.bounded;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/** Holds the stock, and knows how to release it again. */
@Tasklet.Spec(name = "reserve")
public final class Reserve extends Step {

    @Override
    public void run(String order, Ctx<Handle, String> ctx) throws TaskletException {
        // ctx.stableId(), not UUID.randomUUID(): a re-run derives the same id, so the
        // warehouse sees a retry rather than a second reservation.
        String reservationId = ctx.stableId().toString();
        ctx.change(new Handle.Reserved(reservationId));
        ctx.emit(order + "|reservation=" + reservationId);
    }

    @Override
    public void compensate(List<String> completedOutput, Ctx<Handle, String> ctx) {
        // completedOutput can be empty after a crash-resumed rollback. The handle comes
        // from state, which is always restored.
        System.out.println("releasing reservation " + ctx.state().reservationId);
    }
}
