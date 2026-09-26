package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperator.OrderTicket;
import com.flezha.sdk.authoring.State;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Step one: put the money for the order aside, and release it again if the order is undone.
 *
 * <p>{@code run} takes the context because the reservation id comes from {@code ctx.stableId()},
 * so it returns nothing and {@code emits} declares what it hands on.
 */
@Tasklet(id = "reserve_funds", emits = ReserveFunds.Reserved.class)
public final class ReserveFunds {

    /** What it hands on: the order, and the reservation holding the money for it. */
    public record Reserved(OrderTicket order, String reservationId) {}

    /** What this step remembers. The undo handle belongs here: state survives a restart, output does not. */
    @State
    public static class Reservation {
        public String id = "";
    }

    public void run(OrderTicket order, ReservationHandle reservation, TaskletCtx ctx) throws TaskletException {
        // ctx.stableId(), not a random id: a re-executed step derives the same one, so the ledger
        // sees a retry rather than a second sum put aside.
        String id = "res-" + ctx.stableId();
        reservation.setId(id);
        ctx.emit(new Reserved(order, id));
    }

    /**
     * The undo. The bounded driver calls it only when the instance ends abnormally, in reverse
     * order of the steps that finished. It has to be safe to call twice: a crash part way through
     * a rollback runs it again.
     *
     * <p>The id comes from the state, not from {@code completedOutput}: state is restored on
     * resume and emitted output is not, so after a crash-resumed rollback that list can be empty.
     */
    public void compensate(List<Reserved> completedOutput, ReservationHandle reservation) {
        System.out.println("[undo] released " + reservation.get().id);
    }
}
