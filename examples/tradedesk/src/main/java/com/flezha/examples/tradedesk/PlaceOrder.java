package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.examples.tradedesk.ReserveFunds.Reserved;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Step two: place the order with the paper exchange, keeping the id it answers with.
 *
 * <p>It sits on the arm of the fan-out that does the work, beside the risk desk's arm, and like
 * step one it implements {@link Tasklet} directly because it has something to undo.
 */
public final class PlaceOrder
        implements Tasklet<Reserved, PlaceOrder.Placement, PlaceOrder.PlacedOrder, PlaceOrder.Placed, WallClock, Void> {

    /** What this step remembers: the id cancelling the order needs. */
    public static final class Placement {
        public String orderId = "";
    }

    /** The one change it records. */
    public record Placed(String orderId) implements Change {}

    /** What it hands on: the order, and the exchange's id for it. */
    public record PlacedOrder(OrderTicket order, String orderId) {}

    @Override
    public String name() {
        return "place_order";
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(Placement.class, Placed.class);
    }

    @Override
    public Placement initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new Placement();
    }

    @Override
    public void applyChange(Placement state, Placed change) {
        state.orderId = change.orderId();
    }

    @Override
    public void run(Reserved reserved, StateHandle<Placement, Placed> state, TaskletCtx ctx)
            throws TaskletException {
        String orderId = "ord-" + ctx.stableId();
        state.apply(new Placed(orderId));
        ctx.emit(new PlacedOrder(reserved.order(), orderId));
    }

    @Override
    public boolean compensateOverridden() {
        return true;
    }

    /** The undo: the order is cancelled at the exchange, by the id this step wrote down. */
    @Override
    public void compensate(List<PlacedOrder> completedOutput, StateHandle<Placement, Placed> state, TaskletCtx ctx)
            throws TaskletException {
        System.out.println("[undo] cancelled " + state.get().orderId);
    }
}
