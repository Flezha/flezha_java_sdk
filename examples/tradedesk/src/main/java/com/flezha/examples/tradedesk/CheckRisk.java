package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.examples.tradedesk.ReserveFunds.Reserved;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

/**
 * The risk desk's look at the order, which reads what the first step handed on, beside the merge
 * that waits for it.
 *
 * <p>It only reports what it saw, so it keeps no state and has nothing to undo.
 */
public final class CheckRisk implements Procedure<Reserved, Void, CheckRisk.Verdict, NoChange, WallClock, Void> {

    /** What the risk desk says about an order. */
    public record Verdict(OrderTicket order, String verdict) {}

    @Override
    public String name() {
        return "check_risk";
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
    public void run(Reserved reserved, StateHandle<Void, NoChange> state, ProcedureCtx ctx)
            throws ProcedureException {
        OrderTicket order = reserved.order();
        ctx.emit(new Verdict(order, order.quantity() > 100 ? "large" : "ordinary"));
    }
}
