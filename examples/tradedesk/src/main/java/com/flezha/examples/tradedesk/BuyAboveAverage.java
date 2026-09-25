package com.flezha.examples.tradedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.examples.tradedesk.PaperOrderOperator.OrderTicket;
import com.flezha.examples.tradedesk.PricesOperator.Move;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Buys when a price is far enough above its symbol's average.
 *
 * <p>It remembers nothing: the move it is handed already carries the average, so the decision is a
 * function of one value, and a restart costs this stage nothing because there is nothing to bring
 * back. That is why it is written with {@code @Tasklet} and no state, beside {@link TrackPrices},
 * which keeps the window.
 *
 * <p>{@code run} takes the context because it does two things per input: it starts a bounded operator and
 * it emits. {@code emits} declares the output type, which a {@code void} return cannot.
 */
@Tasklet(id = "buy_above_average", emits = String.class)
public final class BuyAboveAverage {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How far above its average a price has to be before the desk buys, in percent. */
    private static final double BUY_ABOVE_PCT = 2.0;

    /** What the desk buys when it buys. */
    private static final int LOT = 10;

    public void run(Move move, TaskletCtx ctx) throws TaskletException {
        double percent = (move.price() - move.average()) / move.average() * 100.0;
        if (percent < BUY_ABOVE_PCT) {
            return;
        }

        byte[] ticket;
        try {
            ticket = JSON.writeValueAsBytes(new OrderTicket(move.symbol(), LOT));
        } catch (Exception unserializable) {
            throw new TaskletException("could not encode the order: " + unserializable.getMessage());
        }

        // No id: the controller names the instance. A replay of this invocation issues no request,
        // so re-running an input never places a second order.
        ctx.spawnBounded("paper_order", ticket);

        ctx.emit(String.format(
                "%s %.2f is %.2f%% above its average of %.2f, buying %d",
                move.symbol(), move.price(), percent, move.average(), LOT));
    }
}
