package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.OrderExecutorOperator.OrderTicket;
import com.flezha.examples.tradedesk.ReserveFunds.Reserved;
import com.flezha.sdk.authoring.State;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Step two: place the order with the paper exchange, keeping the id it answers with, and cancel
 * the order again if it is undone. It has the same shape as step one.
 */
@Tasklet(id = "place_order", emits = PlaceOrder.PlacedOrder.class)
public final class PlaceOrder {

    /** What it hands on: the order, and the exchange's id for it. */
    public record PlacedOrder(OrderTicket order, String orderId) {}

    /** What this step remembers: the id cancelling the order needs. */
    @State
    public static class Placement {
        public String orderId = "";
    }

    public void run(Reserved reserved, PlacementHandle placement, TaskletCtx ctx) throws TaskletException {
        String orderId = "ord-" + ctx.stableId();
        placement.setOrderId(orderId);
        ctx.emit(new PlacedOrder(reserved.order(), orderId));
    }

    /** The undo: the order is cancelled at the exchange, by the id this step wrote down. */
    public void compensate(List<PlacedOrder> completedOutput, PlacementHandle placement) {
        System.out.println("[undo] cancelled " + placement.get().orderId);
    }
}
