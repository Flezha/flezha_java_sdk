package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.examples.tradedesk.PaperOrderOperative.Step;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Step two: place the order with the paper exchange, keeping the id it answers with.
 *
 * <p>Two steps feed this one, so it is handed each of their values in turn, and both have
 * committed before the first of them arrives. The order is placed on the money's value; the risk
 * desk's is only a note.
 */
public final class PlaceOrder
        implements Procedure<Step, PlaceOrder.Placement, PlaceOrder.PlacedOrder, PlaceOrder.Placed, WallClock, Void> {

    /** What this step remembers: the id cancelling the order needs. */
    public static final class Placement {
        public String orderId = "";
    }

    /** The one change it records. */
    public record Placed(String orderId) implements Change {}

    /** What it hands on, and, with {@code cancelled}, what its undo emits. */
    public record PlacedOrder(OrderTicket order, String orderId, boolean cancelled) {}

    @Override
    public String name() {
        return "place_order";
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(Placement.class, Placed.class);
    }

    @Override
    public Placement initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
        return new Placement();
    }

    @Override
    public void applyChange(Placement state, Placed change) {
        state.orderId = change.orderId();
    }

    @Override
    public void run(Step step, StateHandle<Placement, Placed> state, ProcedureCtx ctx) throws ProcedureException {
        if (!"funds".equals(step.source())) {
            return;
        }

        String orderId = "ord-" + ctx.activationToken();
        state.apply(new Placed(orderId));
        ctx.emit(new PlacedOrder(step.order(), orderId, false));
    }

    @Override
    public boolean compensateOverridden() {
        return true;
    }

    /** The undo: the order is cancelled at the exchange, by the id this step wrote down. */
    @Override
    public void compensate(List<PlacedOrder> completedOutput, StateHandle<Placement, Placed> state, ProcedureCtx ctx)
            throws ProcedureException {
        for (PlacedOrder placed : completedOutput) {
            ctx.emit(new PlacedOrder(placed.order(), placed.orderId(), true));
        }
    }
}
