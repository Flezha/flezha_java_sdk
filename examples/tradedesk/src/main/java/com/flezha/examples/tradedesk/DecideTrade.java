package com.flezha.examples.tradedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.examples.tradedesk.CandlesOperative.Candle;
import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.procedure.SpawnOptions;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

/**
 * Reads each closed candle and decides whether the desk trades on it.
 *
 * <p>An order is a workflow of its own, started from here with
 * {@link ProcedureCtx#spawnWorkflow}. Its instance id is built from the symbol and the candle's
 * minute, so the same candle seen twice, after a restart or a replay, reaches the instance that
 * already exists instead of placing a second order.
 */
public final class DecideTrade implements Procedure<Candle, Void, String, NoChange, WallClock, Void> {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The price a candle has to close above before the desk buys, and what it then buys. */
    private static final double BUY_ABOVE = 11.0;

    private static final int LOT = 10;

    @Override
    public String name() {
        return "decide_trade";
    }

    @Override
    public Void initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
        return null;
    }

    @Override
    public void applyChange(Void state, NoChange change) {
        // Nothing is kept: the decision is read off the candle in front of it.
    }

    @Override
    public void run(Candle candle, StateHandle<Void, NoChange> state, ProcedureCtx ctx) throws ProcedureException {
        if (candle.close() <= BUY_ABOVE) {
            ctx.emit("no signal: " + candle);
            return;
        }

        String instanceId = "order-" + candle.symbol() + "-" + candle.minute();
        try {
            ctx.spawnWorkflow(
                    "paper_order",
                    JSON.writeValueAsBytes(new OrderTicket(candle.symbol(), LOT)),
                    SpawnOptions.id(instanceId));
        } catch (Exception spawnRefused) {
            throw new ProcedureException("could not start " + instanceId + ": " + spawnRefused.getMessage());
        }

        ctx.emit("signal: " + candle + " -> " + instanceId);
    }
}
