package com.flezha.examples.tradedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.examples.tradedesk.CandlesOperative.Candle;
import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.tasklet.SpawnOptions;

/**
 * Measures how far a symbol moved to reach the candle that just closed, and buys when the move is
 * large enough.
 *
 * <p>It remembers nothing. The candle it is handed already carries the close before it, so the
 * measurement is a pure function of one value, and a restart costs this stage nothing because
 * there is nothing to bring back. That is why it is written with {@code @Tasklet}: the short form
 * covers a tasklet that is only a {@code run}, and the compiler expands it into the same
 * interface {@link BuildCandles} writes out by hand.
 *
 * <p>{@code run} takes the context because it does two things per input: it starts a workflow and
 * it emits. {@code emits} declares the output type, which a {@code void} return cannot.
 */
@Tasklet(id = "measure_move", emits = String.class)
public final class MeasureMove {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How far a symbol has to rise over one minute before the desk buys, in percent. */
    private static final double BUY_ABOVE_PCT = 2.0;

    /** What the desk buys when it buys. */
    private static final int LOT = 10;

    public void run(Candle candle, TaskletCtx ctx) throws TaskletException {
        // A symbol's first candle has nothing to measure against.
        if (candle.previousClose() <= 0) {
            return;
        }

        double percent = (candle.close() - candle.previousClose()) / candle.previousClose() * 100.0;
        if (percent < BUY_ABOVE_PCT) {
            return;
        }

        byte[] ticket;
        try {
            ticket = JSON.writeValueAsBytes(new OrderTicket(candle.symbol(), LOT));
        } catch (Exception unserializable) {
            throw new TaskletException("could not encode the order: " + unserializable.getMessage());
        }

        // The id is built from the symbol and the candle's minute, so the same candle seen twice
        // reaches the instance that already exists instead of placing a second order. The handle is
        // ignored on purpose: a refused request unread by the end of this invocation logs itself,
        // and the desk carries on building candles.
        String instanceId = "order-" + candle.symbol() + "-" + candle.minute();
        ctx.spawnWorkflow("paper_order", ticket, SpawnOptions.id(instanceId));

        ctx.emit(String.format(
                "%s +%.2f%% to %s, started %s", candle.symbol(), percent, candle.close(), instanceId));
    }
}
