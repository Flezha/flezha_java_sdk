package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.examples.tradedesk.PlaceOrder.PlacedOrder;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

/**
 * Step three: book the fill. The desk refuses an order above its own limit, which is what makes
 * this the step that fails.
 *
 * <p>It has nothing to undo, so it keeps no state and carries no {@code compensate}. A failure
 * here is retried first, and once the retries are spent the instance ends abnormally and the two
 * steps before it are undone in reverse.
 */
public final class ConfirmFill implements Procedure<PlacedOrder, Void, ConfirmFill.Fill, NoChange, WallClock, Void> {

    /** The most one paper order may fill. Anything above it is refused. */
    private static final int LIMIT = 100;

    /** The booked fill, which is what leaves on the operative's sink. */
    public record Fill(OrderTicket order, String orderId) {}

    @Override
    public String name() {
        return "confirm_fill";
    }

    @Override
    public Void initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
        return null;
    }

    @Override
    public void applyChange(Void state, NoChange change) {
        // Nothing is kept.
    }

    @Override
    public void run(PlacedOrder placed, StateHandle<Void, NoChange> state, ProcedureCtx ctx)
            throws ProcedureException {
        int quantity = placed.order().quantity();
        if (quantity > LIMIT) {
            throw new ProcedureException("the desk books at most " + LIMIT + " a time, and this is " + quantity);
        }
        ctx.emit(new Fill(placed.order(), placed.orderId()));
    }
}
